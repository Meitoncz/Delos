#version 450
layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 frag;
layout(set = 0, binding = 0) uniform sampler2D u_image;
void main() { frag=texture(u_image,v_uv); }
