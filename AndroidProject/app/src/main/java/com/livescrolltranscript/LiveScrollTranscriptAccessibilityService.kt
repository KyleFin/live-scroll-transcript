// Copyright 2021 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.livescrolltranscript

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN
import android.view.Display
import android.widget.Toast
import androidx.annotation.RequiresApi

/**
 * An [AccessibilityService] for scrolling on-screen text to match recently-played audio.
 *
 * When the Live Captions view scrolls, this service receives an [AccessibilityEvent]. It then takes
 * a screenshot and uses OCR to read the current Live Caption text. The current accessibility tree
 * is searched for the current caption text. If a unique [AccessibilityNodeInfo] is found to match
 * the caption text, it is requested to show itself on screen.
 */
class LiveScrollTranscriptAccessibilityService : AccessibilityService() {
    private val tag = "LiveScrollTranscriptAccessibilityService"
    private val liveCaptionPackageName = "com.google.android.as"
    private val liveCaptionViewLocation = Rect()
    private val whitespaceRegex = Regex("\\s+")
    private val tryRefresh =
        "Live Scroll Transcript found matching text but failed to scroll. Try reloading the page."

    @RequiresApi(Build.VERSION_CODES.R)
    private val ocrProcessor =
        OcrProcessor(liveCaptionViewLocation, ::scrollToText, this)

    // Number of Live Caption view scrolls that should happen before we search for new caption text.
    private val captionViewScrollsThreshold: Int = 2

    // Number of Live Caption view scrolls that have happened since last search for caption text.
    private var numCaptionViewScrolls: Int = captionViewScrollsThreshold


    override fun onInterrupt() {}

    /**
     * Responds to view scroll events from Live Caption by searching for and showing matching text.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.source?.packageName == liveCaptionPackageName &&
            ++numCaptionViewScrolls >= captionViewScrollsThreshold
        ) {
            numCaptionViewScrolls = 0
            event?.source?.getBoundsInScreen(liveCaptionViewLocation)   // TODO: Lock Rect during OCR.
            takeScreenshot(Display.DEFAULT_DISPLAY, applicationContext.mainExecutor, ocrProcessor)
        }
    }

    /**
     * Attempts to scroll the current screen to display [textToFind].
     *
     * If a unique [AccessibilityNodeInfo] is found to match [textToFind], the node is displayed.
     */
    private fun scrollToText(textToFind: String) {
        val wordsToFind = textToFind.split(whitespaceRegex)
        val keywordIndex = wordsToFind.longestWordIndex()
        val nodesContainingKeyword = mutableSetOf<AccessibilityNodeInfo>()

        Log.d(tag, "wordsToFind: %s".format(wordsToFind))
        Log.d(tag, "keyword: %s".format(wordsToFind[keywordIndex]))

        getNodesContainingWord(
            wordsToFind[keywordIndex], this.rootInActiveWindow, nodesContainingKeyword)

        Log.d(tag, "nodesContainingKeyword.size: ".format(nodesContainingKeyword.size))
        Log.v(tag, nodesContainingKeyword.toString())

        narrowDownNodesContainingKeyword(nodesContainingKeyword, keywordIndex, wordsToFind)
        attemptScroll(nodesContainingKeyword)
        nodesContainingKeyword.forEach(AccessibilityNodeInfo::recycle)
    }

    private fun attemptScroll(nodes: MutableSet<AccessibilityNodeInfo>) {
        if (nodes.size == 1) {
            val scrollSuccess = nodes.first().performAction(ACTION_SHOW_ON_SCREEN.id)
            if (!scrollSuccess) Toast.makeText(this, tryRefresh, Toast.LENGTH_LONG).show()
            Log.i(tag, "SCROLLED  %s".format(scrollSuccess))
        }
    }

    /** Recursive function to find nodes that contain [word]. Recycles nodes that don't match. */
    private fun getNodesContainingWord(
        word: String, root: AccessibilityNodeInfo, result: MutableSet<AccessibilityNodeInfo>
    ) {
        if (root.containsWord(word)) result.add(root)
        for (i in 1..root.childCount) {
            root.getChild(i - 1)?.let { getNodesContainingWord(word, it, result) }
        }
        if (!result.contains(root)) root.recycle()
    }

    // TODO: Unit tests
    /** Narrows down matches by searching for words around keyword. Recycles nodes as removed. */
    private fun narrowDownNodesContainingKeyword(
        nodes: MutableSet<AccessibilityNodeInfo>, keywordIndex: Int, wordsToFind: List<String>
    ) {
        var offset = 1
        while (nodes.size > 1 && offset.magnitudeInBounds(keywordIndex, wordsToFind.size)) {
            Log.d(tag, "offset: %s nodesContaininKeyword.size: %s".format(
                offset, nodes.size))
            nodes.forEach { Log.d(tag, "nodeText: %s".format(it.text)) }
            Log.v(tag, nodes.toString())
            attemptRemovingNodesWithCurrentIndex(nodes, keywordIndex + offset, wordsToFind)
            offset = offset.alternateAndMaybeIncrement()
        }
        Log.d(tag, "nodesContaininKeyword.size: %s".format(nodes.size))
    }

    /** Attempts to recycle nodes not containing [words][[index]]. */
    private fun attemptRemovingNodesWithCurrentIndex(
        nodes: MutableSet<AccessibilityNodeInfo>, index: Int, words: List<String>
    ) {
        if (index > -1 && index < words.size) recycleNodesNotContainingWord(nodes, words[index])
    }

    private fun recycleNodesNotContainingWord(
        nodes: MutableSet<AccessibilityNodeInfo>, word: String
    ) {
        val nodesToRemove = nodes.filter { !it.containsWord(word) }
        nodes.removeAll(nodesToRemove)
        nodesToRemove.forEach(AccessibilityNodeInfo::recycle)
    }

    private fun AccessibilityNodeInfo.containsWord(word: String) =
        text?.contains(word, true) ?: false ||
                contentDescription?.contains(word, true) ?: false

    /** Returns true if using [this] as an offset from [start] is > -1 or < [max] */
    private fun Int.magnitudeInBounds(start: Int, max: Int) =
        start - kotlin.math.abs(this) > -1 || start + kotlin.math.abs(this) < max

    /** Returns -1 * [this] (+1 if result is positive) */
    private fun Int.alternateAndMaybeIncrement() = if (this < 0) (this * -1) + 1 else this * -1

    private fun List<String>.longestWordIndex() = indexOf(maxBy(String::length))
}