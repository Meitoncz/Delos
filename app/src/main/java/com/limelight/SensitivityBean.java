package com.limelight;

/**
 * Description
 * Date: 2024-05-10
 * Time: 23:35
 */
public class SensitivityBean {
   // Real coordinates
   private float lastAbsoluteX =-1;
   private float lastAbsoluteY =-1;

   // Coordinates after applying the sensitivity
   private float lastRelativelyX =-1;
   private float lastRelativelyY =-1;

   public float getLastAbsoluteX() {
      return lastAbsoluteX;
   }

   public void setLastAbsoluteX(float lastAbsoluteX) {
      this.lastAbsoluteX = lastAbsoluteX;
   }

   public float getLastAbsoluteY() {
      return lastAbsoluteY;
   }

   public void setLastAbsoluteY(float lastAbsoluteY) {
      this.lastAbsoluteY = lastAbsoluteY;
   }

   public float getLastRelativelyX() {
      return lastRelativelyX;
   }

   public void setLastRelativelyX(float lastRelativelyX) {
      this.lastRelativelyX = lastRelativelyX;
   }

   public float getLastRelativelyY() {
      return lastRelativelyY;
   }

   public void setLastRelativelyY(float lastRelativelyY) {
      this.lastRelativelyY = lastRelativelyY;
   }
}
