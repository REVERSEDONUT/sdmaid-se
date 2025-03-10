package eu.darken.sdmse.automation.core.common

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import eu.darken.sdmse.automation.core.specs.AutomationExplorer
import eu.darken.sdmse.common.debug.Bugs
import eu.darken.sdmse.common.debug.logging.Logging.Priority.INFO
import eu.darken.sdmse.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.sdmse.common.debug.logging.Logging.Priority.WARN
import eu.darken.sdmse.common.debug.logging.asLog
import eu.darken.sdmse.common.debug.logging.log
import eu.darken.sdmse.common.debug.logging.logTag
import eu.darken.sdmse.common.funnel.IPCFunnel
import eu.darken.sdmse.common.pkgs.Pkg
import eu.darken.sdmse.common.pkgs.features.Installed
import eu.darken.sdmse.common.pkgs.getLabel2
import eu.darken.sdmse.common.pkgs.getPackageInfo2
import eu.darken.sdmse.common.pkgs.getSettingsIntent
import kotlinx.coroutines.delay
import java.util.Locale

private val TAG: String = logTag("Automation", "Crawler", "Common")

fun AutomationExplorer.Context.defaultWindowIntent(
    pkgInfo: Installed
): Intent = pkgInfo.getSettingsIntent(androidContext).apply {
    flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_NO_ANIMATION
}

fun AutomationExplorer.Context.defaultWindowFilter(
    pkgId: Pkg.Id
): (AccessibilityEvent) -> Boolean {
    return fun(event: AccessibilityEvent): Boolean {
        return event.pkgId == pkgId
    }
}

fun AutomationExplorer.Context.defaultClick(
    isDryRun: Boolean = false
): suspend (AccessibilityNodeInfo, Int) -> Boolean = { node: AccessibilityNodeInfo, _: Int ->
    log(TAG, VERBOSE) { "Clicking on ${node.toStringShort()}" }
    if (!node.isEnabled) throw DisabledTargetException("Clickable target is disabled.")
    if (isDryRun) {
        node.performAction(AccessibilityNodeInfo.ACTION_SELECT)
    } else {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}

fun AutomationExplorer.Context.clickableParent(
    maxNesting: Int = 6
): suspend (AccessibilityNodeInfo) -> AccessibilityNodeInfo = { us ->
    us.findParentOrNull(maxNesting = maxNesting) { it.isClickable }
        ?: throw AutomationException("No clickable parent found (within $maxNesting)")
}

fun AutomationExplorer.Context.clickableSibling(): (AccessibilityNodeInfo) -> AccessibilityNodeInfo = { us ->
    us.searchUp(maxNesting = 2) { it.isClickable }
}

fun AutomationExplorer.Context.windowCriteria(
    windowPkgId: Pkg.Id,
    extraTest: (AccessibilityNodeInfo) -> Boolean = { true }
): suspend (AccessibilityNodeInfo) -> Boolean = { node: AccessibilityNodeInfo ->
    node.pkgId == windowPkgId && extraTest(node)
}

suspend fun AutomationExplorer.Context.windowCriteriaAppIdentifier(
    windowPkgId: Pkg.Id,
    ipcFunnel: IPCFunnel,
    pkgInfo: Installed
): suspend (AccessibilityNodeInfo) -> Boolean {
    val candidates = mutableSetOf(pkgInfo.packageName)

    ipcFunnel
        .use {
            packageManager.getLabel2(pkgInfo.id)
        }
        ?.let { candidates.add(it) }

    ipcFunnel
        .use {
            try {
                val activityInfo = packageManager.getPackageInfo2(pkgInfo.id, PackageManager.GET_ACTIVITIES)

                packageManager.getLaunchIntentForPackage(pkgInfo.packageName)?.component
                    ?.let { comp ->
                        activityInfo?.activities?.singleOrNull {
                            it.packageName == comp.packageName && it.name == comp.className
                        }
                    }
                    ?.loadLabel(packageManager)
                    ?.toString()
            } catch (e: Throwable) {
                log(TAG) { "windowCriteriaAppIdentifier error for $pkgInfo: ${e.asLog()}" }
                null
            }
        }
        ?.let { candidates.add(it) }

    pkgInfo.applicationInfo?.className
        ?.let { candidates.add(it) }

    log(TAG, VERBOSE) { "Looking for window identifiers: $candidates" }
    return windowCriteria(windowPkgId) { node ->
        node.crawl().map { it.node }.any { toTest ->
            candidates.any { candidate -> toTest.text == candidate || toTest.text?.contains(candidate) == true }
        }
    }
}

fun AutomationExplorer.Context.getDefaultNodeRecovery(pkg: Installed): suspend (AccessibilityNodeInfo) -> Boolean =
    { root ->
        val busyNode = root.crawl().firstOrNull { it.node.textMatchesAny(listOf("...", "…")) }
        if (busyNode != null) {
            log(TAG, VERBOSE) { "Found a busy-node, attempting recovery via delay: $busyNode" }
            delay(1000)
            root.refresh()
            true
        } else {
            var scrolled = false
            root.crawl()
                .filter { it.node.isScrollable }
                .forEach {
                    val success = it.node.scrollNode()
                    if (success) {
                        scrolled = true
                        it.node.refresh()
                    }
                }
            scrolled
        }
    }

fun AutomationExplorer.Context.getDefaultClearCacheClick(
    pkg: Installed,
    tag: String
): suspend (AccessibilityNodeInfo, Int) -> Boolean = scope@{ node, retryCount ->
    log(tag, VERBOSE) { "Clicking on ${node.toStringShort()} for $pkg:" }

    if (Bugs.isDryRun) {
        log(tag, WARN) { "DRYRUN: Not clicking ${node.toStringShort()}" }
        return@scope true
    }

    try {
        defaultClick(isDryRun = Bugs.isDryRun).invoke(node, retryCount)
    } catch (e: DisabledTargetException) {
        log(tag) { "Can't click on the clear cache button because it was disabled, but why..." }
        try {
            val allButtonsAreDisabled = node.getRoot(maxNesting = 4).crawl().map { it.node }.all {
                !it.isClickyButton() || !it.isEnabled
            }
            if (allButtonsAreDisabled) {
                // https://github.com/d4rken/sdmaid-public/issues/3121
                log(tag, WARN) {
                    "Clear cache button was disabled, but so are others, assuming size calculation going on."
                }
                log(tag) { "Sleeping for 1000ms to wait for calculation." }
                delay((500 * retryCount).toLong())
                false
            } else {
                // https://github.com/d4rken/sdmaid-public/issues/2517
                log(tag, WARN) {
                    "Only the clear cache button was disabled, assuming stale information, counting as success."
                }
                true
            }
        } catch (e: Exception) {
            log(tag, WARN) { "Error while trying to determine why the clear cache button is not enabled." }
            false
        }
    }
}

fun AutomationExplorer.Context.getSysLocale(): Locale {
    val locales = Resources.getSystem().configuration.locales
    log(INFO) { "getSysLocale(): $locales" }
    return locales[0]
}