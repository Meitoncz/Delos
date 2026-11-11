package com.limelight.ui;

/**
 * Where the video goes on the screen: the size {@link StreamContainer} measures itself to for a stream of a given
 * aspect ratio. Pure, so the rectangle can be checked for every screen shape ({@code VideoFitTest}).
 *
 * <p>The container is centered in the room it is given (or on the top edge, "Align top center"), so the bars are
 * what the size leaves over: <b>Fit</b> keeps the whole video and bars the long side of the leftover (letterbox on a
 * screen that is squarer than the video, which is every 4:3 and 1:1 panel showing a 16:9 stream; pillarbox on a
 * screen that is wider); <b>Fill</b> covers the whole screen and the view overflows it on the other axis, so the
 * parent crops it evenly on both sides. Stretch does not come here: the view then simply fills the room, whatever
 * the aspect ratio of the video.
 */
public final class VideoFit {
    private VideoFit() {
    }

    /**
     * The measured {width, height} of a view that shows a video of {@code aspect} (width / height) in
     * {@code availableWidth} x {@code availableHeight} pixels.
     *
     * @param fill true for the Fill scale mode (cover), false for Fit (contain)
     */
    public static int[] measure(int availableWidth, int availableHeight, double aspect, boolean fill) {
        int measuredWidth;
        int measuredHeight;
        if (fill) {
            if (availableWidth < availableHeight * aspect) {
                measuredHeight = availableHeight;
                measuredWidth = (int) (availableHeight * aspect);
            } else {
                measuredWidth = availableWidth;
                measuredHeight = (int) (availableWidth / aspect);
            }
        } else {
            if (availableWidth > availableHeight * aspect) {
                measuredHeight = availableHeight;
                measuredWidth = (int) (measuredHeight * aspect);
            } else {
                measuredWidth = availableWidth;
                measuredHeight = (int) (measuredWidth / aspect);
            }
        }
        return new int[] { measuredWidth, measuredHeight };
    }

    /**
     * The video's rectangle {left, top, right, bottom} on the screen, cropped to it: the measured size placed the way the
     * parent does (centered, or against the top edge when {@code topCenter}).
     */
    public static int[] visibleRect(int availableWidth, int availableHeight, double aspect, boolean fill, boolean topCenter) {
        int[] size = measure(availableWidth, availableHeight, aspect, fill);
        int left = (availableWidth - size[0]) / 2;
        int top = topCenter ? 0 : (availableHeight - size[1]) / 2;
        return new int[] {
                Math.max(left, 0), Math.max(top, 0),
                Math.min(left + size[0], availableWidth), Math.min(top + size[1], availableHeight)
        };
    }
}
