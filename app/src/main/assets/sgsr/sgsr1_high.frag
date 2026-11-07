#version 310 es
// Copyright (c) 2026 Qualcomm Innovation Center, Inc. All rights reserved.
// SPDX-License-Identifier: BSD-3-Clause
// Delos: GLSL port of SGSR_TARGET_HIGH_QUALITY from the official Unity SGSR 1 shader.
// Source: SnapdragonGameStudios/com.qualcomm.snapdragon.sgsr.for.unity
// Commit ab685c993ebd0f65a762c6e28f64f124c7f12532, Shaders/v1/sgsr.hlsl.
// Full precision protects HDR gradients and the variance calculation. Live quality selection
// shares the same input, geometry, P010 conversion and output stage as the Standard variant.
precision highp float;
precision highp int;
uniform highp vec4 ViewportInfo[1];
uniform highp sampler2D ps0;
uniform int sgsrEnabled;
layout(location=0) in highp vec4 in_TEXCOORD0;
layout(location=0) out vec4 out_Target0;
float saturate(float v) { return clamp(v,0.0,1.0); }
vec4 SGSRRH(vec2 p) { return textureGather(ps0,p,0); }
vec4 SGSRGH(vec2 p) { return textureGather(ps0,p,1); }
vec4 SGSRBH(vec2 p) { return textureGather(ps0,p,2); }
vec4 SGSRRGBH(vec2 p) { return textureLod(ps0,p,0.0); }
float fastLanczos2(float x)
{
    float wA = (x + -4.0);
    float wB = x * wA - wA;
    wA *= wA;
    return wB * wA;
}

vec4 weightY(float dx, float dy, float c, vec2 dir, float std)
{
    float dxy2 = (dx * dx) + (dy * dy);
    float c2std = c * c * std;

    float dc = exp(c2std);
    float ds = sqrt(dxy2);

    float edgeDis = ((dx * dir.y) + (dy * dir.x));
    edgeDis *= edgeDis;

    float x1 = dxy2+(edgeDis * (saturate(c2std*-2.8) * 0.7 + -1.0));
    float w1 = fastLanczos2(x1);

    float x2 = (ds * 0.54 + 0.36) - dc * 0.36;
    float w2 = fastLanczos2(x2);

    return vec4(w1 * c, w1, w2 * c, w2);
}

vec2 calDirection(vec4 left, vec4 right) {
    vec2 dir;
    float RxLz = (right.x + (-left.z));
    float RwLy = (right.w + (-left.y));
    vec2 delta;
    delta.x = (RxLz + RwLy);
    delta.y = (RxLz + (-RwLy));
    float lengthInv = inversesqrt((delta.x * delta.x+ 3.075740e-05) + (delta.y * delta.y));
    dir.x = (delta.x * lengthInv);
    dir.y = (delta.y * lengthInv);
    return dir;
}

vec2 sgsrPass(vec4 left, vec4 right, vec4 upDown, vec2 pl)
{
    float sum2 = left.x * left.x + left.y * left.y + left.z * left.z + left.w * left.w +
        right.x * right.x + right.y * right.y + right.z * right.z + right.w * right.w +
        upDown.x * upDown.x + upDown.y * upDown.y + upDown.z * upDown.z + upDown.w * upDown.w;
    float std2 = -6.0 / max(sum2, 1e-8);

    vec2 dir = calDirection(left, right);

    vec4 aYW = weightY(pl.x, pl.y + 1.0, upDown.x, dir, std2);
    aYW += weightY(pl.x - 1.0, pl.y + 1.0, upDown.y, dir, std2);
    aYW += weightY(pl.x - 1.0, pl.y - 2.0, upDown.z, dir, std2);
    aYW += weightY(pl.x, pl.y - 2.0, upDown.w, dir, std2);

    aYW += weightY(pl.x + 1.0, pl.y - 1.0, left.x, dir, std2);
    aYW += weightY(pl.x + 1.0, pl.y, left.w, dir, std2);
    aYW += weightY(pl.x - 2.0, pl.y - 1.0, right.y, dir, std2);
    aYW += weightY(pl.x - 2.0, pl.y, right.z, dir, std2);

    aYW += weightY(pl.x, pl.y - 1.0, left.y, dir, std2);
    aYW += weightY(pl.x, pl.y, left.z, dir, std2);
    aYW += weightY(pl.x - 1.0, pl.y - 1.0, right.x, dir, std2);
    aYW += weightY(pl.x - 1.0, pl.y, right.w, dir, std2);

    return vec2(aYW.x / aYW.y, aYW.z / aYW.w);
}

vec4 textureGatherMode(vec2 coord){
    float Kr = 0.299;
    float Kg = 0.587;
    float Kb = 0.114;

    vec4 left;

        left.xyzw =   SGSRRH(coord)   * vec4(Kr)	+ SGSRGH(coord)   * vec4(Kg)	+ SGSRBH(coord)   * vec4(Kb);

    return left;
}

void SgsrYuvH(
    out vec4 pix,
    vec2 uv,
    vec4 con1){

    float edgeThreshold = (4.0/255.0);
    float edgeSharpness = 2.0;

        pix.xyzw = SGSRRGBH(uv).xyzw;

    float Kr = 0.299;
    float Kg = 0.587;
    float Kb = 0.114;

    {
        vec4 upDown;
        vec4 right;
        vec4 left;
        vec2 pl;
        vec2 coord;
        vec2 imgCoord;
        imgCoord.xy = ((uv * con1.zw) + vec2(-0.5, 0.5));
        vec2 imgCoordPixel = floor(imgCoord);
        coord.xy = (imgCoordPixel * con1.xy);
        pl.xy = (imgCoord + (-imgCoordPixel));

        left = textureGatherMode(coord);

            pix.w = pix.x * Kr + pix.y * Kg + pix.z * Kb;

        float edgeVote = abs(left.z - left.y) + abs(pix.w - left.y) + abs(pix.w - left.z);
        if (edgeVote > edgeThreshold) {

            coord.x += con1.x;

            right = textureGatherMode(coord + vec2(con1.x, 0.0));
            upDown.xy = textureGatherMode(coord + vec2(0.0, -con1.y)).wz;
            upDown.zw = textureGatherMode(coord + vec2(0.0, con1.y)).yx;

            float mean = ((((left.y + left.z) + right.x) + right.w) * 0.25);
            left.xyzw = left - vec4(mean);
            right.xyzw = right - vec4(mean);
            upDown.xyzw = upDown - vec4(mean);
            pix.w =pix.w - mean;

            vec2 y2 = sgsrPass(left, right, upDown, pl);

            float maxY = max(max(left.y, left.z), max(right.x, right.w));
            float minY = min(min(left.y, left.z), min(right.x, right.w));

            float edgeY = clamp(edgeSharpness * y2.x, minY, maxY);
            float smoothY = clamp(edgeSharpness * y2.y, minY, maxY);

            float deltaY = (smoothY *  0.4- pix.w) + (edgeY * 0.6 );
            pix.x = saturate(pix.x + deltaY);
            pix.y = saturate(pix.y + deltaY);
            pix.z = saturate(pix.z + deltaY);
        }
    }

    pix.w = 1.0;  

}
void main() {
    vec4 color = textureLod(ps0,in_TEXCOORD0.xy,0.0);
    if (sgsrEnabled != 0) SgsrYuvH(color,in_TEXCOORD0.xy,ViewportInfo[0]);
    color.w = 1.0;
    out_Target0.xyzw = color;
}
