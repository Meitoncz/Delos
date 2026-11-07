#version 310 es
// Copyright (c) 2026 Delos contributors. GPL-3.0-or-later.
// Sharpen only: no scaling, one contrast-adaptive sharpen pass at the stream's own resolution.
// FidelityFX CAS (MIT, AMD) on the five-sample cross at a scale factor of 1, per colour channel, in the
// encoded domain (SDR gamma or HDR PQ code values). SharpenOnly.java is the CPU reference of these steps;
// keep the two in step. sgsrStrength 0 returns the pixel unchanged.
precision highp float;
uniform highp sampler2D ps0;
uniform highp vec4 ViewportInfo[1];
uniform float sgsrStrength;
layout(location=0) in highp vec4 in_TEXCOORD0;
layout(location=0) out vec4 out_Target0;
void main() {
    vec2 uv=in_TEXCOORD0.xy,stepSize=ViewportInfo[0].xy;
    vec3 e=textureLod(ps0,uv,0.0).rgb;
    vec3 result=e;
    if(sgsrStrength>0.0) {
        vec3 b=textureLod(ps0,uv-vec2(0,stepSize.y),0.0).rgb;
        vec3 d=textureLod(ps0,uv-vec2(stepSize.x,0),0.0).rgb;
        vec3 f=textureLod(ps0,uv+vec2(stepSize.x,0),0.0).rgb;
        vec3 h=textureLod(ps0,uv+vec2(0,stepSize.y),0.0).rgb;
        vec3 mn=min(min(min(d,e),min(f,b)),h);
        vec3 mx=max(max(max(d,e),max(f,b)),h);
        vec3 amp=sqrt(clamp(min(mn,1.0-mx)/max(mx,vec3(1e-4)),0.0,1.0));
        // 1/5 is CAS's strongest peak; the square root keeps 50 % close to CAS's default of 0.154
        vec3 w=-amp*(0.2*sqrt(clamp(sgsrStrength,0.0,1.0)));
        result=clamp(((b+d+f+h)*w+e)/(1.0+4.0*w),0.0,1.0);
    }
    vec4 color=vec4(result,1.0);
    out_Target0.xyzw = color;
}
