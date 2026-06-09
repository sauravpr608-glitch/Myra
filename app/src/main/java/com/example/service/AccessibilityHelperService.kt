package com.example.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.engine.SocialEngine

class AccessibilityHelperService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val platform = SocialEngine.pendingPlatform ?: return
        val content = SocialEngine.pendingPostContent ?: return

        val packageName = event.packageName?.toString() ?: ""
        Log.d("MyraAccessibility", "Accessibility event in: $packageName")

        // Only trigger code automation when in targeted user-opened application
        val isTwitter = (platform == "twitter" || platform == "x") && packageName.contains("twitter")
        val isInstagram = platform == "instagram" && packageName.contains("instagram")

        if (isTwitter || isInstagram) {
            val rootNode = rootInActiveWindow ?: return
            
            // Step 1: Look for composer or create post button, auto-click if found
            if (isTwitter) {
                automateTwitterposting(rootNode, content)
            } else if (isInstagram) {
                automateInstagramposting(rootNode, content)
            }
        }
    }

    private fun automateTwitterposting(root: AccessibilityNodeInfo, content: String) {
        // Look for tweet/compose floating action button and click
        // Standard Twitter IDs or descriptions or text
        val composeNode = findNodeByTextOrDesc(root, listOf("Compose Tweet", "Tweet", "New Post", "What's happening?", "Write tweet", "Draft", "+"))
        if (composeNode != null) {
            if (composeNode.isClickable) {
                composeNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d("MyraAccessibility", "Clicked Twitter compose button successfully.")
            } else {
                composeNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }

        // Look for input field "What's happening?", paste clipboard / set text
        val inputNode = findNodeByTextOrDesc(root, listOf("What's happening?", "Adhure", "Tweet text", "Write something", "Post text", "Compose text"))
        if (inputNode != null) {
            val arguments = Bundle().apply {
                putCharSequence("ACTION_ARG_SET_TEXT_CHARSEQUENCE", content)
            }
            inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            Log.d("MyraAccessibility", "Pasted post content into Twitter compose field.")

            // Reset pending, so we don't repeat infinitely
            SocialEngine.pendingPostContent = null
            SocialEngine.pendingPlatform = null
        }
    }

    private fun automateInstagramposting(root: AccessibilityNodeInfo, content: String) {
        // Look for create post button (usually a camera icon, plus icon, "New post", or "+" content descriptor)
        val createNode = findNodeByTextOrDesc(root, listOf("New Post", "Create", "Add Post", "CAMERA", "plus", "+"))
        if (createNode != null) {
            createNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d("MyraAccessibility", "Clicked Instagram plus/create button.")
        }

        // Look for feed caption input or writing text box
        val captionNode = findNodeByTextOrDesc(root, listOf("Write a caption", "Caption", "Instagram text", "Enter caption"))
        if (captionNode != null) {
            val arguments = Bundle().apply {
                putCharSequence("ACTION_ARG_SET_TEXT_CHARSEQUENCE", content)
            }
            captionNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            Log.d("MyraAccessibility", "Pasted Instagram caption successfully.")

            SocialEngine.pendingPostContent = null
            SocialEngine.pendingPlatform = null
        }
    }

    private fun findNodeByTextOrDesc(root: AccessibilityNodeInfo, triggers: List<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""

            for (trigger in triggers) {
                val t = trigger.lowercase()
                if (text.contains(t) || desc.contains(t) || text == t || desc == t) {
                    return node
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    queue.add(child)
                }
            }
        }
        return null
    }

    override fun onInterrupt() {
        Log.d("MyraAccessibility", "Accessibility Service interrupted.")
    }

    override fun onServiceConnected() {
        Log.d("MyraAccessibility", "Accessibility Service connected successfully.")
    }
}
