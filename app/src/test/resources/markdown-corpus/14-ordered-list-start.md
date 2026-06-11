## Ordered Lists — Non-Default Start Number

CommonMark allows ordered lists to start at any number. The first item's number determines the start value; subsequent items' numbers are ignored by the spec (they are always incremented by one in rendered HTML), but many renderers simply use the numbers as written.

This list starts at 7:

7. Open the **Run** menu in Android Studio
8. Select **Edit Configurations…**
9. Click the **+** button and choose **Android App**
10. Set the **Module** field to `:app`
11. Choose a deployment target (emulator or physical device)
12. Click **OK** to save the configuration
13. Press **Shift+F10** (or the green play button) to launch

The parser must produce an ordered list with `start="7"` (or equivalent) so the rendered output begins at 7, not 1.

### Another List Starting at 42

Sometimes an answer continues a list that was started elsewhere in conversation:

42. Check that `adb devices` lists your device
43. Enable USB debugging in **Developer Options**
44. Accept the RSA fingerprint prompt on the device
45. Verify the device shows as `device` (not `unauthorized`)

### Regression: Tight vs Loose

A tight list (no blank lines between items) starting at 7:

7. First tight item
8. Second tight item
9. Third tight item

A loose list (blank lines between items) starting at 7:

7. First loose item

8. Second loose item

9. Third loose item

In the loose variant each item's content should be wrapped in a paragraph tag internally.
