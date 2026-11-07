// The presentation filters of the SGSR present and pack passes. Both SDR and HDR are processed in their
// encoded domain. In particular, HDR samples remain PQ code values: never apply an SDR gamma or quantize
// to 8 bits.
layout(push_constant) uniform Presentation {
    float sharpness;
    // 0: SGSR 2's bounded presentation filter (same as assets/sgsr/temporal_present.frag).
    // 1: Sharpen only's unchanged contrast-adaptive sharpen (SharpenOnly.java is the CPU reference).
    // 2: SGSR 1's bounded presentation filter, with half-strength independent sharpening.
    int contrastAdaptive;
} presentation;
vec3 contrastAdaptiveColor(vec2 uv) {
    vec3 e=texture(u_image,uv).rgb;
    float strength=clamp(presentation.sharpness,0.0,1.0);
    if(strength==0.0) return e;
    vec2 stepSize=1.0/vec2(textureSize(u_image,0));
    vec3 b=texture(u_image,uv-vec2(0,stepSize.y)).rgb;
    vec3 d=texture(u_image,uv-vec2(stepSize.x,0)).rgb;
    vec3 f=texture(u_image,uv+vec2(stepSize.x,0)).rgb;
    vec3 h=texture(u_image,uv+vec2(0,stepSize.y)).rgb;
    vec3 mn=min(min(min(d,e),min(f,b)),h);
    vec3 mx=max(max(max(d,e),max(f,b)),h);
    vec3 amp=sqrt(clamp(min(mn,1.0-mx)/max(mx,vec3(1e-4)),0.0,1.0));
    vec3 w=-amp*(0.2*sqrt(strength));
    return clamp(((b+d+f+h)*w+e)/(1.0+4.0*w),0.0,1.0);
}
vec3 presentationColor(vec2 uv) {
    if(presentation.contrastAdaptive==1) return contrastAdaptiveColor(uv);
    vec3 center=texture(u_image,uv).rgb;
    float strength=clamp(presentation.sharpness,0.0,1.0);
    // SGSR 1: total detail gain is .375*s (default s=.5 gives .1875), before unchanged limits.
    if(presentation.contrastAdaptive==2) strength*=.5;
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
