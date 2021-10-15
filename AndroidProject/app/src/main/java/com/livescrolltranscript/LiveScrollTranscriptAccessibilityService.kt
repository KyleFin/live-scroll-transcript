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
import androidx.annotation.RequiresApi
import java.lang.Math.abs

class LiveScrollTranscriptAccessibilityService : AccessibilityService() {
    private val tag = "LiveScrollTranscriptAccessibilityService"
    private val liveCaptionPackageName = "com.google.android.as"
    private val liveCaptionViewLocation = Rect()
    private val whitespaceRegex = Regex("\\s+")

    @RequiresApi(Build.VERSION_CODES.R)
    private val ocrProcessor = OcrProcessor(::scrollToCurrentText, liveCaptionViewLocation)

    private val captionBoxScrollsThreshold: Int = 2
    private var numCaptionBoxScrolls: Int = captionBoxScrollsThreshold
    private var processingScroll = false    // TODO: Better locking (ensure it gets released on failure)

    override fun onInterrupt() {}

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.source?.packageName?.equals(liveCaptionPackageName) == true &&
            ++numCaptionBoxScrolls >= captionBoxScrollsThreshold &&
            !processingScroll
        ) {
            processingScroll = true
            Log.d(tag, "processingScroll = %s".format(processingScroll))
            numCaptionBoxScrolls = 0

            event?.source?.getBoundsInScreen(liveCaptionViewLocation)   // TODO: Lock Rect during OCR.
            takeScreenshot(Display.DEFAULT_DISPLAY, applicationContext.mainExecutor, ocrProcessor)
        }
    }

    private fun scrollToCurrentText(onScreenCaptionText: String) {
        val captionWords = onScreenCaptionText.split(whitespaceRegex)
        val keywordIndex = getKeywordIndex(captionWords)
        val nodesContainingKeyword = mutableSetOf<AccessibilityNodeInfo>()

        fun getNodesContainingCurrentCaption(node: AccessibilityNodeInfo) {
            if (nodeContainsWord(node, captionWords[keywordIndex])) {
                nodesContainingKeyword.add(node)
            }
            // TODO: Recycle NodeInfo when they're not needed (in else or when removing from set? https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getChild(int)
            for (i in 1..node.childCount) {
                node.getChild(i - 1)?.let { getNodesContainingCurrentCaption(it) }
            }
        }

        // TODO: Unit test
        fun narrowDownNodesContainingKeyword() {
            var offset = 1
            var currIndex: Int
            fun offsetStillInBounds(): Boolean {
                return keywordIndex - abs(offset) > -1 ||
                        keywordIndex + abs(offset) < captionWords.size
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
                if (currIndex > -1 && currIndex < captionWords.size) {
                    nodesContainingKeyword.removeIf { !nodeContainsWord(it, captionWords[currIndex]) }
                }
                alternateAndMaybeIncrementOffset()

                Log.d(tag, nodesContainingKeyword.size.toString())
                Log.d(tag, nodesContainingKeyword.toString())
            }
        }

        Log.d(tag, captionWords.toString())
        Log.d(tag, captionWords[keywordIndex])

        getNodesContainingCurrentCaption(this.rootInActiveWindow)

        Log.d(tag, nodesContainingKeyword.size.toString())
        Log.d(tag, nodesContainingKeyword.toString())
        narrowDownNodesContainingKeyword()

        if (nodesContainingKeyword.size == 1) {
            val scrollSuccess = nodesContainingKeyword.first().performAction(ACTION_SHOW_ON_SCREEN.id)
            Log.i(tag, "SCROLLED  %s".format(scrollSuccess))
        }
        processingScroll = false
        Log.d(tag, "processingScroll = %s".format(processingScroll))
    }

    private fun getKeywordIndex(words: List<String>): Int {
        return words.indexOf(words.maxBy(String::length))
    }

    private fun nodeContainsWord(node: AccessibilityNodeInfo, word: String): Boolean {
        return node?.text?.contains(word, true) ?: false ||
                node?.contentDescription?.contains(word, true) ?: false
    }
}