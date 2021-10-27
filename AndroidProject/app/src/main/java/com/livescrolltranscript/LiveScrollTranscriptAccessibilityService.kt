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
import java.lang.Math.abs

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
    private val tryRefresh = "Live Scroll Transcript found matching text but failed to scroll. Try reloading the page."

    @RequiresApi(Build.VERSION_CODES.R)
    private val ocrProcessor = OcrProcessor(liveCaptionViewLocation, ::scrollToText)

    // Number of Live Caption view scrolls that should happen before we search for new caption text.
    private val captionViewScrollsThreshold: Int = 2

    // Number of Live Caption view scrolls that have happened since last search for caption text.
    private var numCaptionViewScrolls: Int = captionViewScrollsThreshold

    private var processingScroll = false    // TODO: Better locking (ensure it gets released on failure)

    override fun onInterrupt() {}

    /**
     * Responds to view scroll events from Live Caption by searching for and showing matching text.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.source?.packageName?.equals(liveCaptionPackageName) == true &&
            ++numCaptionViewScrolls >= captionViewScrollsThreshold &&
            !processingScroll
        ) {
            processingScroll = true
            Log.d(tag, "processingScroll = %s".format(processingScroll))
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
        val keywordIndex = getLongestWordIndex(wordsToFind)
        val nodesContainingKeyword = mutableSetOf<AccessibilityNodeInfo>()

        /** Recursive local function to find nodes that contain keyword. */
        fun getNodesContainingKeyword(node: AccessibilityNodeInfo) {
            if (nodeContainsWord(node, wordsToFind[keywordIndex])) {
                nodesContainingKeyword.add(node)
            }
            for (i in 1..node.childCount) {
                node.getChild(i - 1)?.let { getNodesContainingKeyword(it) }
            }
            // TODO: Recycle nodes when not needed (here, in else, or when removing from set?)
            // https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getChild(int)
        }

        /** Local functions to narrow down matches by searching for words before/after keyword. */
        fun narrowDownNodesContainingKeyword() {    // TODO: Unit test
            var offset = 1
            var currIndex: Int
            fun offsetStillInBounds(): Boolean {
                return keywordIndex - abs(offset) > -1 ||
                        keywordIndex + abs(offset) < wordsToFind.size
            }
            fun alternateAndMaybeIncrementOffset() {
                offset *= -1
                if (offset > 0) offset++
            }

            while (nodesContainingKeyword.size > 1 && offsetStillInBounds())
            {
                Log.d(tag, "offset: %s nodesContaininKeyword.size: %s".format(
                    offset, nodesContainingKeyword.size))
                currIndex = keywordIndex + offset
                if (currIndex > -1 && currIndex < wordsToFind.size) {
                    nodesContainingKeyword.removeIf { !nodeContainsWord(it, wordsToFind[currIndex]) }
                }
                alternateAndMaybeIncrementOffset()

                Log.d(tag, nodesContainingKeyword.size.toString())
                Log.d(tag, nodesContainingKeyword.toString())
            }
        }

        Log.d(tag, wordsToFind.toString())
        Log.d(tag, wordsToFind[keywordIndex])

        getNodesContainingKeyword(this.rootInActiveWindow)

        Log.d(tag, nodesContainingKeyword.size.toString())
        Log.d(tag, nodesContainingKeyword.toString())

        narrowDownNodesContainingKeyword()

        if (nodesContainingKeyword.size == 1) {
            val scrollSuccess = nodesContainingKeyword.first().performAction(ACTION_SHOW_ON_SCREEN.id)
            if (!scrollSuccess) Toast.makeText(this, tryRefresh, Toast.LENGTH_LONG).show()
            Log.i(tag, "SCROLLED  %s".format(scrollSuccess))
        }
        processingScroll = false
        Log.d(tag, "processingScroll = %s".format(processingScroll))
    }

    private fun getLongestWordIndex(words: List<String>): Int {
        return words.indexOf(words.maxBy(String::length))
    }

    private fun nodeContainsWord(node: AccessibilityNodeInfo, word: String): Boolean {
        return node?.text?.contains(word, true) ?: false ||
                node?.contentDescription?.contains(word, true) ?: false
    }
}