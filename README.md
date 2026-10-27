# What is this?
An Xposed module that hooks into systemui where the volume bar gradient is drawn to unlock more colors for you to set.

# Install
Pretty easy, just make sure you are **running crDroid with root**. I used crDroid 12.11, you need a version where they introduced the gradient for the volume slider and qs/brightness slider.
Make sure that you also have XPosed installed on your device. The module uses that to hook into where the gradient is drawn.
Install the APK using adb sideload or manually onto your phone, then enable the module and tick SystemUI to be on. (also grant root)

# Setting the colors
Once you install, restart the System UI to apply the changes. You can then go ahead and copy the example commands that set the gradients. Or make your own!
