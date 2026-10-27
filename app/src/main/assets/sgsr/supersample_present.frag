#version 310 es
// Copyright (c) 2026 Delos contributors. GPL-3.0-or-later.
// Linear sampling exactly averages a 2x2 footprint for the 2x mode. The 4/3 mode remains a
// reconstruction-to-presentation resolve without applying another sharpening pass.
precision highp float;
uniform highp sampler2D ps0;
layout(location=0) in highp vec4 in_TEXCOORD0;
layout(location=0) out vec4 out_Target0;
void main() {
    vec4 color=textureLod(ps0,in_TEXCOORD0.xy,0.0);
    out_Target0.xyzw = color;
}
