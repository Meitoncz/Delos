#version 310 es
// Copyright (c) 2026 Delos contributors. GPL-3.0-or-later.
// SGSR 1 final resolve and independent bounded sharpening. The input stays RGB/PQ until
// the output conversion, and the slider never changes reconstruction gain.
precision highp float;
uniform highp sampler2D ps0;
uniform highp vec4 ViewportInfo[1];
uniform float sgsrStrength;
layout(location=0) in highp vec4 in_TEXCOORD0;
layout(location=0) out vec4 out_Target0;
void main() {
    vec2 uv=in_TEXCOORD0.xy;
    vec3 center=textureLod(ps0,uv,0.0).rgb;
    vec3 result=center;
    if (sgsrStrength>0.0) {
        // Match the native bounded filter's reconstructed-image texel spacing.
        vec2 stepSize=ViewportInfo[0].xy;
        vec3 left=textureLod(ps0,uv-vec2(stepSize.x,0),0.0).rgb;
        vec3 right=textureLod(ps0,uv+vec2(stepSize.x,0),0.0).rgb;
        vec3 down=textureLod(ps0,uv-vec2(0,stepSize.y),0.0).rgb;
        vec3 up=textureLod(ps0,uv+vec2(0,stepSize.y),0.0).rgb;
        vec3 low=min(center,min(min(left,right),min(down,up)));
        vec3 high=max(center,max(max(left,right),max(down,up)));
        vec3 limit=min(vec3(0.06),(high-low)*0.25);
        vec3 detail=center-(left+right+down+up)*0.25;
        result=clamp(center+clamp(detail*sgsrStrength*0.375,-limit,limit),0.0,1.0);
    }
    vec4 color=vec4(result,1.0);
    out_Target0.xyzw = color;
}
