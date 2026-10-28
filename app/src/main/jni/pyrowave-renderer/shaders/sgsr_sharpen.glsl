// Same bounded presentation filter as assets/sgsr/temporal_present.frag.
// Both SDR and HDR are processed in their encoded domain. In particular, HDR
// samples remain PQ code values: never apply an SDR gamma or quantize to 8 bits.
layout(push_constant) uniform Presentation {
    float sharpness;
    int hdr;
} presentation;
vec3 presentationColor(vec2 uv) {
    vec3 center=texture(u_image,uv).rgb;
    float strength=clamp(presentation.sharpness,0.0,1.0);
    if(strength==0.0) return center;
    vec2 stepSize=1.0/vec2(textureSize(u_image,0));
    vec3 left=texture(u_image,uv-vec2(stepSize.x,0)).rgb;
    vec3 right=texture(u_image,uv+vec2(stepSize.x,0)).rgb;
    vec3 down=texture(u_image,uv-vec2(0,stepSize.y)).rgb;
    vec3 up=texture(u_image,uv+vec2(0,stepSize.y)).rgb;
    vec3 low=min(center,min(min(left,right),min(down,up)));
    vec3 high=max(center,max(max(left,right),max(down,up)));
    vec3 limit=min(vec3(.06),(high-low)*.25);
    vec3 detail=center-(left+right+down+up)*.25;
    return clamp(center+clamp(detail*strength*.75,-limit,limit),0.0,1.0);
}
