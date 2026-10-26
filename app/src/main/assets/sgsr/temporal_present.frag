#version 310 es
// Copyright (c) 2026 Delos contributors. GPL-3.0-or-later.
// Presentation-only sharpening: the sharpened result is never accumulated into history.
precision highp float;
uniform highp sampler2D ps0;
uniform highp vec4 ViewportInfo[1];
uniform float sgsrStrength;
layout(location=0) in highp vec4 in_TEXCOORD0;
layout(location=0) out vec4 out_Target0;
void main() {
    vec2 uv=in_TEXCOORD0.xy,stepSize=ViewportInfo[0].xy;
    vec3 center=textureLod(ps0,uv,0.0).rgb;
    vec3 left=textureLod(ps0,uv-vec2(stepSize.x,0),0.0).rgb;
    vec3 right=textureLod(ps0,uv+vec2(stepSize.x,0),0.0).rgb;
    vec3 down=textureLod(ps0,uv-vec2(0,stepSize.y),0.0).rgb;
    vec3 up=textureLod(ps0,uv+vec2(0,stepSize.y),0.0).rgb;
    vec3 low=min(center,min(min(left,right),min(down,up)));
    vec3 high=max(center,max(max(left,right),max(down,up)));
    vec3 limit=min(vec3(0.06),(high-low)*0.25);
    vec3 detail=center-(left+right+down+up)*0.25;
    vec4 color=vec4(clamp(center+clamp(detail*sgsrStrength*0.75,-limit,limit),0.0,1.0),1.0);
    out_Target0.xyzw = color;
}
