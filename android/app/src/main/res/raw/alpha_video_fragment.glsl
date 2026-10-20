#extension GL_OES_EGL_image_external : require
precision mediump float;

// Vidéo « côte à côte » : moitié gauche = couleurs, moitié droite = transparence (blanc = opaque).
uniform samplerExternalOES uObject;
uniform sampler2D uSampler;
uniform float uAlpha;

varying vec2 vTextureCoord;
varying vec2 vTextureObjectCoord;

void main() {
  vec4 color = texture2D(uSampler, vTextureCoord);
  if (vTextureObjectCoord.x < 0.0 || vTextureObjectCoord.x > 1.0 ||
  vTextureObjectCoord.y < 0.0 || vTextureObjectCoord.y > 1.0) {
    gl_FragColor = color;
  } else {
    // 0.498 / 0.502 : évite que le filtrage mélange les deux moitiés à la jonction
    vec3 rgb = texture2D(uObject, vec2(vTextureObjectCoord.x * 0.498, vTextureObjectCoord.y)).rgb;
    float a = texture2D(uObject, vec2(0.502 + vTextureObjectCoord.x * 0.498, vTextureObjectCoord.y)).r;
    a = smoothstep(0.03, 0.97, a);
    gl_FragColor = vec4(mix(color.rgb, rgb, a * uAlpha), 1.0);
  }
}
