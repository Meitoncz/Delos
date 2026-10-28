#version 450
#extension GL_GOOGLE_include_directive : require
layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 frag;
layout(set = 0, binding = 0) uniform sampler2D u_image;
#include "sgsr_sharpen.glsl"
void main() { frag=vec4(presentationColor(v_uv),1.0); }
