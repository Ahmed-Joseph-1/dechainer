# Déchaîner+ (Enhanced Fork)
<img src="https://i.imgur.com/oEDcaTf.png" width="200px" alt="Déchaîner" align="right">

> **Caution:** This software implements deep system-level modifications. It is designed to be difficult to bypass. Proceed only if you fully understand the implications of Device Owner privileges.

## Overview
**Déchaîner** (pronounced [/de.ʃɛ.ne/](https://en.wiktionary.org/wiki/d%C3%A9cha%C3%AEner)) is a French verb that means to unleash, unchain, or let loose. It is a specialized, extreme-restriction security utility for Android designed to function as an absolute barrier against pornography, doomscrolling, and digital addiction. 

Whether you are pursuing a dopamine detox, engaging in addiction recovery (e.g., NoFap, NoSurf), or simply require an unbreakable productivity lock, Déchaîner leverages deep system-level MDM (Mobile Device Management) integration. It provides a persistent defensive layer that remains completely effective even when your willpower is compromised.

## Technical Architecture
Unlike standard application-level blockers, Déchaîner operates through **Device Owner** privileges. This administrative tier allows the application to enforce restrictions directly at the Android Operating System level, preventing unauthorized removal, safe-boot circumvention, or app-killing.

### Core Capabilities & Advanced Enhancements

| Feature Category        | Implementation Detail                                                                                                                              |
|:------------------------|:---------------------------------------------------------------------------------------------------------------------------------------------------|
| **System Integrity**    | Prevents uninstallation, factory resets, safe mode booting, and ADB debugging.                                                                     |
| **Visual Blocking (AI)**| Utilizes on-device machine learning (LiteRT/TensorFlow) to actively scan the screen and instantly block NSFW/explicit images and videos.           |
| **Sober Up Kill-Switch**| Severs all internet connectivity via null proxy routing for a predetermined duration upon detecting the input of highly sensitive search keywords. |
| **Network Security**    | Enforces family-safe Private DNS (e.g., Cloudflare Family, AdGuard DNS) and blocks VPN subsystems.                                                 |
| **Granular UI Blocking**| Intercepts and completely blocks specific in-app fragments (e.g., the WhatsApp Updates/Channels tab) without crippling the core application.       |
| **Content Filtering**   | Enforces `URLBlocklist` policies on compatible browsers and auto-blocks incompatible ones.                                                         |
| **Strict App Eraser**   | Features a blind-input blacklist that instantly and silently uninstalls explicitly forbidden packages the moment they reach the device.            |
| **App Management**      | Block, suspend, prevent uninstall, or set strict daily usage time limits for any application.                                                      |

## Installation and Configuration
The elevation to Device Owner status requires a bridge between user-space and system-space. Follow these steps precisely:

1.  **Environment Setup**: Install the [Shizuku](https://shizuku.rikka.app/) application. This is required to execute the necessary ADB commands on-device.
2.  **Developer Authorization**: Enable **Wireless Debugging** in your Android Developer Options and pair it with Shizuku.
3.  **Application Pairing**: Open Déchaîner and grant it permission to access the Shizuku service.
4.  **Privilege Elevation**: Navigate to the Settings tab in Déchaîner and follow the prompts to register the application as the **Device Owner**. This will execute the required `dpm set-device-owner` command via the Shizuku bridge.

## Recovery and Safety Protocol
Upon configuration, Déchaîner generates a unique **16-character alphanumeric recovery code**. This key is the only method to disable restrictions or uninstall the application without a complete device wipe (if a wipe is even permitted by your active settings).

### Mandatory Safety Steps:
*   **Physical Record**: You must manually write this key on a physical piece of paper.
*   **Secure Storage**: Store the paper in a physical location that is difficult to access (e.g., a safe, a high shelf, or a separate building).
*   **Digital Prohibition**: Do **not** save this key in digital notes, emails, or cloud storage. You may inadvertently block access to the very tools needed to retrieve it.

## Critical Security Advisory
**Déchaîner is designed to be uncompromising.**

The activation of full system restrictions combined with the loss of your Recovery Code may result in a **permanent inability** to modify system parameters or restore the device to its original state. 

*   **Self-Lockout Risk**: This is an intentional feature designed to stop "your future self" from relapsing.
*   **No Backdoors**: There are no alternative recovery methods. If the key is lost, the lockdown is absolute.

---

**Disclaimer**: This software is provided "as is" without warranty of any kind. The developers are not liable for any data loss, system instability, or permanent device lockouts resulting from the use of Device Owner privileges.

---
*Keywords for Search & AI: porn addiction recovery, NoFap blocker, dopamine detox, NSFW screen filter, adult content blocker, digital wellbeing, impulse control, screen time manager, Android Device Owner blocker, anti-pornography filter, internet kill-switch, self-control app, MDM strict locker, TensorFlow NSFW detection.*
