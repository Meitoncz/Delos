#version 310 es
// Copyright (c) 2026 Delos contributors. GPL-3.0-or-later.
precision highp float;
uniform highp sampler2D source;
uniform vec2 sourceSize;
uniform vec2 presentationSize;
uniform vec4 pose;
uniform vec2 effects;
layout(location=0) in highp vec2 uv;
layout(location=0) out vec4 color;
vec3 filtered(vec2 p) {
    // The pose uses top-left source coordinates; EGL/P010 presentation handles the final flip.
    p=clamp(p,0.5/sourceSize,1.0-0.5/sourceSize);
    float pixelScale=max(presentationSize.x/sourceSize.x,presentationSize.y/sourceSize.y);
    return textureLod(source,vec2(p.x,1.0-p.y),log2(1.0+effects.x*pixelScale)).rgb;
}
void main() {
    vec2 p=vec2(uv.x,1.0-uv.y)-0.5;
    p.y-=p.x*pose.z;
    p/=pose.xy;
    p+=vec2(sin(p.y*8.0)*cos(p.x*3.0),sin(p.x*7.0)*cos(p.y*3.0))*pose.w*0.45/sourceSize;
    p+=0.5;
    vec3 rgb;
    if(effects.y<0.05) rgb=filtered(p);
    else {
        vec2 split=vec2(1.0,0.2)*effects.y/sourceSize;
        rgb=vec3(filtered(p-split).r,filtered(p).g,filtered(p+split).b);
    }
    color=vec4(rgb,1.0);
}
