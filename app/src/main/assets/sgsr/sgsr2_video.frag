#version 310 es
// Delos: video adaptation, total optical flow, confidence rejection and zero engine jitter.

//============================================================================================================
//
//
//                  Copyright (c) 2024, Qualcomm Innovation Center, Inc. All rights reserved.
//                              SPDX-License-Identifier: BSD-3-Clause
//
//============================================================================================================

precision highp float;
precision highp int;

float FastLanczos(float base)
{
	float y = base - 1.0f;
	float y2 = y * y;
	float y_temp = 0.75f * y + y2;
	return y_temp * y2;
}

layout(location = 0) out highp vec4 Output;
layout(location = 0) in highp vec2 texCoord;

uniform highp sampler2D PrevOutput;
uniform highp sampler2D MotionDepthClipAlphaBuffer;
uniform highp sampler2D InputColor;

uniform highp vec2 renderSize;
uniform highp vec2 outputSize;
uniform highp vec2 renderSizeRcp;
uniform highp vec2 scaleRatio;
uniform float reset;
const vec2 jitterOffset=vec2(0.0);
const float minLerpContribution=0.0;
uniform highp sampler2D ForwardFlow;
uniform highp sampler2D SceneChange;
uniform highp sampler2D ReactiveMask;
uniform int qualityMode;
vec3 rgbToYCoCg(vec3 c) { return vec3(c.r*0.25+c.g*0.5+c.b*0.25,c.r*0.5-c.b*0.5,-c.r*0.25+c.g*0.5-c.b*0.25); }
vec3 yCoCgToRgb(vec3 c) { return vec3(c.x+c.y-c.z,c.x+c.z,c.x-c.y-c.z); }
vec2 flowUv(vec2 uv) {
    return ((uv*renderSize-vec2(0.5))/8.0)/vec2(textureSize(MotionDepthClipAlphaBuffer,0));
}
vec4 fetchCurrent(ivec2 p) {
    return texelFetch(InputColor,clamp(p,ivec2(0),ivec2(renderSize)-1),0);
}

void main()
{
    float scalefactor = scaleRatio.y;

    highp vec2 Hruv = texCoord;

    highp vec2 Jitteruv;
    Jitteruv.x = clamp(Hruv.x + (jitterOffset.x * renderSizeRcp.x), 0.0, 1.0);
    Jitteruv.y = clamp(Hruv.y + (jitterOffset.y * renderSizeRcp.y), 0.0, 1.0);

    highp ivec2 InputPos = ivec2(Jitteruv * renderSize);

    // RG = current-to-previous source-pixel flow; B = residual; A = confidence.
    vec4 backward=textureLod(MotionDepthClipAlphaBuffer,flowUv(Jitteruv),0.0);
    vec2 rawPrevious=Jitteruv+backward.xy*renderSizeRcp;
    vec4 forward=textureLod(ForwardFlow,flowUv(rawPrevious),0.0);
    float consistency=length(backward.xy+forward.xy);
    float confidence=min(backward.w,forward.w)*exp(-consistency*1.5);
    if(any(lessThan(rawPrevious,vec2(0.0)))||any(greaterThan(rawPrevious,vec2(1.0)))) confidence=0.0;
    float frameReset=max(reset,textureLod(SceneChange,vec2(0.5),0.0).r);
    highp vec3 mda=vec3(-2.0*backward.xy*renderSizeRcp,1.0-confidence);
    highp vec2 Motion = mda.xy;

    highp vec2 PrevUV;
    PrevUV.x = clamp(-0.5 * Motion.x + Hruv.x, 0.0, 1.0);
#ifdef REQUEST_NDC_Y_UP
    PrevUV.y = clamp(0.5 * Motion.y + Hruv.y, 0.0, 1.0);
#else
    PrevUV.y = clamp(-0.5 * Motion.y + Hruv.y, 0.0, 1.0);
#endif

    float depthfactor = mda.z;

    vec3 HistoryColor = textureLod(PrevOutput, PrevUV, 0.0).xyz;

    /////upsample and compute box
    vec4 Upsampledcw = vec4(0.0);
    // Engine SGSR2 adjusts the footprint for jittered render samples. Decoded video has zero
    // engine jitter: use a fixed source-pixel Lanczos footprint. Scaling it by output/input
    // makes the signed weight sum cross zero, amplifying tiny differences into phase speckle.
    float kernelbias = 1.0;
    float motion_viewport_len = length(Motion * outputSize);
    float curvebias = mix(-2.0, -3.0, clamp(motion_viewport_len * 0.02, 0.0, 1.0));

    vec3 rectboxcenter = vec3(0.0);
    vec3 rectboxvar = vec3(0.0);
    float rectboxweight = 0.0;
    highp vec2 srcpos = vec2(InputPos) + vec2(0.5) - jitterOffset;

    kernelbias *= 0.5f;
    float kernelbias2 = kernelbias * kernelbias;
    vec2 srcpos_srcOutputPos = srcpos - Hruv * renderSize;  //srcOutputPos = Hruv * renderSize;
    vec3 rectboxmin;
    vec3 rectboxmax;
    vec3 topMid = fetchCurrent(InputPos + ivec2(0, 1)).xyz;
    {

        vec3 samplecolor = topMid;
        vec2 baseoffset = srcpos_srcOutputPos + vec2(0.0, 1.0);
        float baseoffset_dot = dot(baseoffset, baseoffset);
        float base = clamp(baseoffset_dot * kernelbias2, 0.0f, 1.0f);
        float weight = FastLanczos(base);
        Upsampledcw += vec4(samplecolor * weight, weight);
        float boxweight = exp(baseoffset_dot * curvebias);
        rectboxmin = samplecolor;
        rectboxmax = samplecolor;
        vec3 wsample = samplecolor * boxweight;
        rectboxcenter += wsample;
        rectboxvar += (samplecolor * wsample);
        rectboxweight += boxweight;
    }
    vec3 rightMid = fetchCurrent(InputPos + ivec2(1, 0)).xyz;
    {

        vec3 samplecolor = rightMid;
        vec2 baseoffset = srcpos_srcOutputPos + vec2(1.0, 0.0);
        float baseoffset_dot = dot(baseoffset, baseoffset);
        float base = clamp(baseoffset_dot * kernelbias2, 0.0f, 1.0f);
        float weight = FastLanczos(base);
        Upsampledcw += vec4(samplecolor * weight, weight);
        float boxweight = exp(baseoffset_dot * curvebias);
        rectboxmin = min(rectboxmin, samplecolor);
        rectboxmax = max(rectboxmax, samplecolor);
        vec3 wsample = samplecolor * boxweight;
        rectboxcenter += wsample;
        rectboxvar += (samplecolor * wsample);
        rectboxweight += boxweight;
    }
    vec3 leftMid = fetchCurrent(InputPos + ivec2(-1, 0)).xyz;
    {

        vec3 samplecolor = leftMid;
        vec2 baseoffset = srcpos_srcOutputPos + vec2(-1.0, 0.0);
        float baseoffset_dot = dot(baseoffset, baseoffset);
        float base = clamp(baseoffset_dot * kernelbias2, 0.0f, 1.0f);
        float weight = FastLanczos(base);
        Upsampledcw += vec4(samplecolor * weight, weight);
        float boxweight = exp(baseoffset_dot * curvebias);
        rectboxmin = min(rectboxmin, samplecolor);
        rectboxmax = max(rectboxmax, samplecolor);
        vec3 wsample = samplecolor * boxweight;
        rectboxcenter += wsample;
        rectboxvar += (samplecolor * wsample);
        rectboxweight += boxweight;
    }
    vec3 centerMid = fetchCurrent(InputPos + ivec2(0, 0)).xyz;
    {

        vec3 samplecolor = centerMid;
        vec2 baseoffset = srcpos_srcOutputPos;
        float baseoffset_dot = dot(baseoffset, baseoffset);
        float base = clamp(baseoffset_dot * kernelbias2, 0.0f, 1.0f);
        float weight = FastLanczos(base);
        Upsampledcw += vec4(samplecolor * weight, weight);
        float boxweight = exp(baseoffset_dot * curvebias);
        rectboxmin = min(rectboxmin, samplecolor);
        rectboxmax = max(rectboxmax, samplecolor);
        vec3 wsample = samplecolor * boxweight;
        rectboxcenter += wsample;
        rectboxvar += (samplecolor * wsample);
        rectboxweight += boxweight;
    }
    vec3 btmMid = fetchCurrent(InputPos + ivec2(0, -1)).xyz;
    {

        vec3 samplecolor = btmMid;
        vec2 baseoffset = srcpos_srcOutputPos + vec2(0.0, -1.0);
        float baseoffset_dot = dot(baseoffset, baseoffset);
        float base = clamp(baseoffset_dot * kernelbias2, 0.0f, 1.0f);
        float weight = FastLanczos(base);
        Upsampledcw += vec4(samplecolor * weight, weight);
        float boxweight = exp(baseoffset_dot * curvebias);
        rectboxmin = min(rectboxmin, samplecolor);
        rectboxmax = max(rectboxmax, samplecolor);
        vec3 wsample = samplecolor * boxweight;
        rectboxcenter += wsample;
        rectboxvar += (samplecolor * wsample);
        rectboxweight += boxweight;
    }

    //if (sameCameraFrmNum!=0u)  //maybe disable this for ultra performance
    if (true)  //maybe disable this for ultra performance, true could generate more realistic output
    {
        {
            vec3 topRight = fetchCurrent(InputPos + ivec2(1, 1)).xyz;
            vec3 samplecolor = topRight;
            vec2 baseoffset = srcpos_srcOutputPos + vec2(1.0, 1.0);
            float baseoffset_dot = dot(baseoffset, baseoffset);
            float base = clamp(baseoffset_dot * kernelbias2, 0.0, 1.0);
            float weight = FastLanczos(base);
            Upsampledcw += vec4(samplecolor * weight, weight);
            float boxweight = exp(baseoffset_dot * curvebias);
            rectboxmin = min(rectboxmin, samplecolor);
            rectboxmax = max(rectboxmax, samplecolor);
            vec3 wsample = samplecolor * boxweight;
            rectboxcenter += wsample;
            rectboxvar += (samplecolor * wsample);
            rectboxweight += boxweight;
        }
        {
            vec3 topLeft = fetchCurrent(InputPos + ivec2(-1, 1)).xyz;
            vec3 samplecolor = topLeft;
            vec2 baseoffset = srcpos_srcOutputPos + vec2(-1.0, 1.0);
            float baseoffset_dot = dot(baseoffset, baseoffset);
            float base = clamp(baseoffset_dot * kernelbias2, 0.0f, 1.0f);
            float weight = FastLanczos(base);
            Upsampledcw += vec4(samplecolor * weight, weight);
            float boxweight = exp(baseoffset_dot * curvebias);
            rectboxmin = min(rectboxmin, samplecolor);
            rectboxmax = max(rectboxmax, samplecolor);
            vec3 wsample = samplecolor * boxweight;
            rectboxcenter += wsample;
            rectboxvar += (samplecolor * wsample);
            rectboxweight += boxweight;
        }
        {
            vec3 btmRight = fetchCurrent(InputPos + ivec2(1, -1)).xyz;
            vec3 samplecolor = btmRight;
            vec2 baseoffset = srcpos_srcOutputPos + vec2(1.0, -1.0);
            float baseoffset_dot = dot(baseoffset, baseoffset);
            float base = clamp(baseoffset_dot * kernelbias2, 0.0f, 1.0f);
            float weight = FastLanczos(base);
            Upsampledcw += vec4(samplecolor * weight, weight);
            float boxweight = exp(baseoffset_dot * curvebias);
            rectboxmin = min(rectboxmin, samplecolor);
            rectboxmax = max(rectboxmax, samplecolor);
            vec3 wsample = samplecolor * boxweight;
            rectboxcenter += wsample;
            rectboxvar += (samplecolor * wsample);
            rectboxweight += boxweight;
        }

        {
            vec3 btmLeft = fetchCurrent(InputPos + ivec2(-1, -1)).xyz;
            vec3 samplecolor = btmLeft;
            vec2 baseoffset = srcpos_srcOutputPos + vec2(-1.0, -1.0);
            float baseoffset_dot = dot(baseoffset, baseoffset);
            float base = clamp(baseoffset_dot * kernelbias2, 0.0f, 1.0f);
            float weight = FastLanczos(base);
            Upsampledcw += vec4(samplecolor * weight, weight);
            float boxweight = exp(baseoffset_dot * curvebias);
            rectboxmin = min(rectboxmin, samplecolor);
            rectboxmax = max(rectboxmax, samplecolor);
            vec3 wsample = samplecolor * boxweight;
            rectboxcenter += wsample;
            rectboxvar += (samplecolor * wsample);
            rectboxweight += boxweight;
        }
    }

    rectboxweight = 1.0 / rectboxweight;
    rectboxcenter *= rectboxweight;
    rectboxvar *= rectboxweight;
    rectboxvar = sqrt(abs(rectboxvar - rectboxcenter * rectboxcenter));

    // Preserve signed Lanczos normalization; a negative lobe sum must not become +epsilon.
    // Engine SGSR 2 can tolerate a small reconstruction overshoot because its input comes from a
    // jittered game render. A decoded video frame already contains resolved, high-contrast UI edges;
    // the previous +/-0.075 allowance produced visible bright and dark outlines around them. Clamp
    // the signed-Lanczos result to the actual 3x3 source neighbourhood to suppress ringing without
    // changing the temporal motion/history path.
    Upsampledcw.xyz = abs(Upsampledcw.w)>1e-6 ?
        clamp(Upsampledcw.xyz/Upsampledcw.w,rectboxmin,rectboxmax) :
        textureLod(InputColor,Hruv,0.0).rgb;
    Upsampledcw.w = abs(Upsampledcw.w) * (1.0f / 3.0f);

    float baseupdate = 1.0f - depthfactor;
    baseupdate = min(baseupdate, mix(baseupdate, Upsampledcw.w *10.0f, clamp(10.0f* motion_viewport_len, 0.0, 1.0)));
    baseupdate = min(baseupdate, mix(baseupdate, Upsampledcw.w, clamp(motion_viewport_len *0.05f, 0.0, 1.0)));
    float basealpha = baseupdate;

    const float EPSILON = 1.192e-07f;
    float boxscale = max(depthfactor, clamp(motion_viewport_len * 0.05f, 0.0, 1.0));
    float boxsize = mix(scalefactor, 1.0f, boxscale);
    vec3 sboxvar = rectboxvar * boxsize;
    vec3 boxmin = rectboxcenter - sboxvar;
    vec3 boxmax = rectboxcenter + sboxvar;
    rectboxmax = min(rectboxmax, boxmax);
    rectboxmin = max(rectboxmin, boxmin);

    vec3 clampedcolor;
    if(qualityMode != 0) {
        vec3 ycHistory=rgbToYCoCg(HistoryColor);
        vec3 ycCenter=rgbToYCoCg(rectboxcenter);
        vec3 ycMin=vec3(1e10),ycMax=vec3(-1e10);
        for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++) {
            vec3 yc=rgbToYCoCg(fetchCurrent(InputPos+ivec2(x,y)).rgb);
            ycMin=min(ycMin,yc);ycMax=max(ycMax,yc);
        }
        vec3 ycExtent=(ycMax-ycMin)*0.5;
        vec3 ycClamped=clamp(ycHistory,max(ycMin,ycCenter-ycExtent),min(ycMax,ycCenter+ycExtent));
        clampedcolor=yCoCgToRgb(ycClamped);
    } else clampedcolor = clamp(HistoryColor, rectboxmin, rectboxmax);
    float startLerpValue = minLerpContribution;
    if ((abs(mda.x) + abs(mda.y)) > 0.000001) startLerpValue = 0.0;
    float lerpcontribution = (any(greaterThan(rectboxmin, HistoryColor)) || any(greaterThan(HistoryColor, rectboxmax))) ? startLerpValue : 1.0f;

    HistoryColor = mix(clampedcolor, HistoryColor, clamp(lerpcontribution, 0.0, 1.0));
    float basemin = min(basealpha, 0.1f);
    basealpha = mix(basemin, basealpha, clamp(lerpcontribution, 0.0, 1.0));

    ////blend color
    float alphasum = max(EPSILON, basealpha + Upsampledcw.w);
    float alpha = clamp(Upsampledcw.w / alphasum + frameReset, 0.0, 1.0);
    // Flow confidence replaces engine depth rejection. Never blend untrusted history.
    float reactive=qualityMode != 0 ? textureLod(ReactiveMask,Jitteruv,0.0).r : 0.0;
    alpha=max(alpha,1.0-confidence*0.75);
    alpha=max(alpha,reactive);

    Upsampledcw.xyz = mix(HistoryColor, Upsampledcw.xyz, alpha);

    Output = vec4(Upsampledcw.xyz, 0.0);
}
