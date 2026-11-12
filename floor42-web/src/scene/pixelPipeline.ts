import * as THREE from 'three';

/** The composite pass's full-screen quad. */
const VERTEX = /* glsl */ `
        varying vec2 vUv;
        void main() { vUv = uv; gl_Position = vec4(position.xy, 0.0, 1.0); }`;

/** Outlines on depth and normal edges, and the blur of the far distance. */
const FRAGMENT = /* glsl */ `
        uniform sampler2D tColor;
        uniform sampler2D tDepth;
        uniform sampler2D tNormal;
        uniform vec2 resolution;
        uniform float near;
        uniform float far;
        uniform float highlight;
        uniform float shadowLine;
        uniform float blur;
        uniform float focus;
        varying vec2 vUv;

        float eyeDepth(vec2 uv) { return near + texture2D(tDepth, uv).r * (far - near); }
        vec3 normalAt(vec2 uv) { return texture2D(tNormal, uv).rgb * 2.0 - 1.0; }

        void main() {
          vec2 t = 1.0 / resolution;
          vec4 base = texture2D(tColor, vUv);
          float d = eyeDepth(vUv);
          vec3 n = normalAt(vUv);
          vec2 offsets[4] = vec2[](vec2(0.0, 1.0), vec2(0.0, -1.0), vec2(1.0, 0.0), vec2(-1.0, 0.0));

          float depthEdge = 0.0;
          float normalEdge = 0.0;
          for (int i = 0; i < 4; i++) {
            vec2 uv = vUv + offsets[i] * t;
            float dd = eyeDepth(uv) - d;
            depthEdge += clamp(dd - 0.12, 0.0, 1.0);
            vec3 nn = normalAt(uv);
            float bias = dot(n - nn, vec3(1.0, 1.0, 1.0));
            float convex = smoothstep(-0.01, 0.01, bias);
            float sameSurface = step(abs(dd), 0.12);
            normalEdge += (1.0 - dot(n, nn)) * convex * sameSurface;
          }
          depthEdge = step(0.01, depthEdge);
          normalEdge = step(0.2, normalEdge) * (1.0 - depthEdge);

          vec3 c = base.rgb;
          // Far away (the city below) and zoomed out: a soft blur, like a camera focused on the office.
          float k = blur * smoothstep(focus, focus + 25.0, d);
          if (k > 0.01) {
            vec3 sum = c;
            float r = 1.0 + 2.5 * k;
            for (int i = 0; i < 12; i++) {
              float a = float(i) * 0.5236;
              sum += texture2D(tColor, vUv + vec2(cos(a), sin(a)) * t * r * (1.0 + float(i % 2))).rgb;
            }
            c = mix(c, sum / 13.0, k);
            depthEdge *= 1.0 - k;
          }
          c *= 1.0 - shadowLine * depthEdge;
          c *= 1.0 + highlight * normalEdge;
          gl_FragColor = vec4(c, 1.0);
          #include <colorspace_fragment>
        }`;

/**
 * Renders the scene at low resolution, then upscales with nearest filtering and adds pixel-art outlines:
 * dark lines on depth discontinuities (silhouettes) and light lines on convex creases (normal changes).
 */
export class PixelPipeline {
  readonly renderer: THREE.WebGLRenderer;
  pixelSize: number;

  private color!: THREE.WebGLRenderTarget;
  private normals!: THREE.WebGLRenderTarget;
  private readonly normalMaterial = new THREE.MeshNormalMaterial();
  private readonly quadScene = new THREE.Scene();
  private readonly quadCamera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1);
  private readonly composite: THREE.ShaderMaterial;

  constructor(canvas: HTMLCanvasElement, pixelSize: number) {
    this.pixelSize = pixelSize;
    this.renderer = new THREE.WebGLRenderer({ canvas, antialias: false });
    this.renderer.shadowMap.enabled = true;
    this.renderer.shadowMap.type = THREE.PCFShadowMap;
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;

    this.composite = new THREE.ShaderMaterial({
      uniforms: {
        tColor: { value: null },
        tDepth: { value: null },
        tNormal: { value: null },
        resolution: { value: new THREE.Vector2() },
        near: { value: 0.1 },
        far: { value: 100 },
        highlight: { value: 0.32 },
        shadowLine: { value: 0.5 },
        // Depth of field: beyond the focus depth, the picture blurs by up to `blur` (0 sharp, 1 most).
        blur: { value: 0 },
        focus: { value: 62 },
      },
      vertexShader: VERTEX,
      fragmentShader: FRAGMENT,
      depthTest: false,
      depthWrite: false,
    });
    this.quadScene.add(new THREE.Mesh(new THREE.PlaneGeometry(2, 2), this.composite));
  }

  /** How blurred the far distance is: 0 sharp, 1 most. */
  setBlur(amount: number): void {
    this.composite.uniforms.blur.value = amount;
  }

  /** Size of one low-resolution pixel, in CSS pixels, is `pixelSize`. */
  resize(width: number, height: number): THREE.Vector2 {
    this.renderer.setPixelRatio(1);
    this.renderer.setSize(width, height, false);
    const w = Math.max(1, Math.floor(width / this.pixelSize));
    const h = Math.max(1, Math.floor(height / this.pixelSize));
    this.color?.dispose();
    this.normals?.dispose();
    const opts = { minFilter: THREE.NearestFilter, magFilter: THREE.NearestFilter, generateMipmaps: false };
    this.color = new THREE.WebGLRenderTarget(w, h, { ...opts, depthTexture: new THREE.DepthTexture(w, h) });
    this.normals = new THREE.WebGLRenderTarget(w, h, opts);
    this.composite.uniforms.resolution.value.set(w, h);
    return new THREE.Vector2(w, h);
  }

  /** Frees the render targets, the pipeline's own materials and quad, then the renderer and its WebGL context. */
  dispose(): void {
    this.color?.depthTexture?.dispose();
    this.color?.dispose();
    this.normals?.dispose();
    this.normalMaterial.dispose();
    this.composite.dispose();
    this.quadScene.traverse((o) => (o as Partial<THREE.Mesh>).geometry?.dispose());
    this.renderer.dispose();
    // Release the GPU context at once: a new decade builds a new canvas and a new one.
    this.renderer.forceContextLoss();
  }

  render(scene: THREE.Scene, camera: THREE.OrthographicCamera): void {
    const r = this.renderer;
    this.composite.uniforms.near.value = camera.near;
    this.composite.uniforms.far.value = camera.far;

    r.setRenderTarget(this.color);
    r.render(scene, camera);
    this.renderNormals(scene, camera);

    this.composite.uniforms.tColor.value = this.color.texture;
    this.composite.uniforms.tDepth.value = this.color.depthTexture;
    this.composite.uniforms.tNormal.value = this.normals.texture;
    r.setRenderTarget(null);
    r.render(this.quadScene, this.quadCamera);
  }

  /** Normal pass: sprites and unlit overlays hidden so they do not create fake creases, no background. */
  private renderNormals(scene: THREE.Scene, camera: THREE.OrthographicCamera): void {
    const hidden: THREE.Object3D[] = [];
    scene.traverse((o) => {
      if (o.visible && o.userData.noOutline) {
        o.visible = false;
        hidden.push(o);
      }
    });
    const background = scene.background;
    scene.background = null;
    scene.overrideMaterial = this.normalMaterial;
    this.renderer.setRenderTarget(this.normals);
    this.renderer.render(scene, camera);
    scene.overrideMaterial = null;
    scene.background = background;
    hidden.forEach((o) => (o.visible = true));
  }
}
