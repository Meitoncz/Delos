#version 450
#extension GL_GOOGLE_include_directive : require
// PQ BT.2020 RGB -> the existing FP16 YCbCr/P010 GL bridge, unchanged dataspace/metadata.
layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 emit;
#include "arrival_sample.glsl"
void main() {
    vec3 rgb = arrivalColor(v_uv);
    const float kr=.2627, kb=.0593, kg=1.0-kr-kb;
    float y=kr*rgb.r+kg*rgb.g+kb*rgb.b;
    float cb=(rgb.b-y)/(2.0*(1.0-kb));
    float cr=(rgb.r-y)/(2.0*(1.0-kr));
    vec3 limited=vec3(64.0/1023.0+y*876.0/1023.0,
                      512.0/1023.0+cb*896.0/1023.0,
                      512.0/1023.0+cr*896.0/1023.0);
    emit=vec4(clamp(limited,0.0,1.0),1.0);
}
