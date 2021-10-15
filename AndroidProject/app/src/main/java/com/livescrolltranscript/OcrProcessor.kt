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
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

@RequiresApi(Build.VERSION_CODES.R)
class OcrProcessor(callback: (String) -> Unit, captionViewLocation: Rect) :
    TakeScreenshotCallback {
    private val tag = "livescrolltranscript.ScreenshotCallback"
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val callback = callback
    private val captionViewLocation = captionViewLocation

    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
        getCaptionText(screenshot)
    }

    override fun onFailure(errorCode: Int) {
        Log.w(tag, "Screenshot failed with errorCode: %s".format(errorCode))
    }

    private fun getCaptionText(screenshot: AccessibilityService.ScreenshotResult): Task<Text> {
        val hwBuffer = screenshot.hardwareBuffer
        val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
            ?.copy(Bitmap.Config.ARGB_8888, false)
        hwBuffer.close()
        val croppedBitmap = bitmap?.let {
            Bitmap.createBitmap(
                it,
                captionViewLocation.left,
                captionViewLocation.top,
                captionViewLocation.width(),
                captionViewLocation.height())
        }
        return recognizer.process(InputImage.fromBitmap(croppedBitmap, 0))
            .addOnSuccessListener { visionText ->
                callback(visionText.text)
            }
            .addOnFailureListener { e ->
                Log.e(tag, "OCR Failed")
                e.printStackTrace()
            }
    }


}