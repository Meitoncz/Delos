#version 450
#extension GL_GOOGLE_include_directive : require
layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 emit;
#include "arrival_sample.glsl"
void main() { emit = vec4(arrivalColor(v_uv), 1); }
