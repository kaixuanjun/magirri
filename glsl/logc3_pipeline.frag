#version 300 es
/*
 * Magic-Log -> LogC3 -> ARRI Look 实时管线（OpenGL ES 3.0 片段着色器）
 * ------------------------------------------------------------------
 * 与 magicrri/pipeline.py、magicrri/glsl_mirror.py 逐阶段对应：
 *
 *   采样 Magic-Log 编码值 (0..1)
 *     -> 还原 LUT（输入: Magic-Log 码值 / 输出: Rec.709 OETF 码值）
 *     -> Rec.709 OETF 逆变换
 *     -> Rec.709 -> AWG3 基色变换
 *     -> LogC3 (EI800) 编码
 *     -> ARRI Look LUT（输入: LogC3/AWG3，输出: Rec.709/SDR）
 *
 * 所有 LUT 都是 GL_TEXTURE_3D + GL_RGB16F，数据布局为 [r][g][b]，
 * 即 x 轴（R）在内存中变化最快，与 glTexImage3D 的约定一致。
 *
 * 注意：GLSL ES 3.0 没有 log10()，必须用 log2()*log2(10)^-1 代替，
 * 且必须使用 highp，否则 10-bit 信号的精度会在中间调被吃掉。
 */

precision highp float;
precision highp sampler3D;
precision highp sampler2D;

uniform sampler2D uLogTexture;      // Magic-Log 输入（GL_RGB10_A2 或 GL_RGBA16F，归一化到 0..1）
uniform sampler3D uRestoreLut;
uniform sampler3D uLookLut;

uniform ivec3 uRestoreSize;         // 例如 ivec3(33)
uniform ivec3 uLookSize;
uniform vec3  uRestoreDomainMin;
uniform vec3  uRestoreDomainMax;
uniform vec3  uLookDomainMin;
uniform vec3  uLookDomainMax;

uniform mat3  uM709ToAwg3;
uniform int   uDecodeMode;          // 0 = Rec.709 OETF 逆（正确） 1 = gamma 2.4 近似 2 = 跳过
uniform int   uGamutConvert;        // 0 = 跳过 1 = 做 709->AWG3
uniform int   uInterp;             // 0 = 四面体 1 = 三线性
uniform int   uClipOutput;

in  vec2 vTexCoord;
out vec4 fragColor;

#define LOG10(x) (log2(x) * 0.30102999566398120)

const float LOGC3_CUT = 0.010591;
const float LOGC3_A   = 5.555556;
const float LOGC3_B   = 0.052272;
const float LOGC3_C   = 0.247190;
const float LOGC3_D   = 0.385537;
const float LOGC3_E   = 5.367655;
const float LOGC3_F   = 0.092809;

/* Rec.709 OETF 的逆：把显示编码值还原为线性光。
   分段点 0.081 对应线性 0.018。负值按线性段外延。 */
float rec709OetfInverse(float v) {
    if (v < 0.081) {
        return v * (1.0 / 4.5);
    }
    return pow(max((v + 0.099) * (1.0 / 1.099), 0.0), 1.0 / 0.45);
}

/* 线性 -> LogC3 (EI 800)。线性 1.0 编码为 0.5706，注意 LUT 采样域是 [0,1]。 */
float logc3Encode(float x) {
    if (x > LOGC3_CUT) {
        return LOGC3_C * LOG10(LOGC3_A * x + LOGC3_B) + LOGC3_D;
    }
    return LOGC3_E * x + LOGC3_F;
}

vec3 decodeToLinear(vec3 c) {
    if (uDecodeMode == 0) {
        return vec3(rec709OetfInverse(c.r), rec709OetfInverse(c.g), rec709OetfInverse(c.b));
    } else if (uDecodeMode == 1) {
        return pow(max(c, 0.0), vec3(2.4));
    }
    return c;
}

/* 3D LUT 采样。data[r][g][b] -> texelFetch(lut, ivec3(r,g,b), 0) */
vec3 sampleLut3D(sampler3D lut, ivec3 size, vec3 domMin, vec3 domMax, vec3 rgb, int interp) {
    vec3 span = max(domMax - domMin, vec3(1e-8));
    vec3 q = clamp((rgb - domMin) / span, 0.0, 1.0);
    vec3 pos = q * vec3(size - ivec3(1));
    ivec3 i0 = clamp(ivec3(floor(pos)), ivec3(0), size - ivec3(2));
    vec3 f = pos - vec3(i0);

    vec3 c000 = texelFetch(lut, i0 + ivec3(0, 0, 0), 0).rgb;
    vec3 c100 = texelFetch(lut, i0 + ivec3(1, 0, 0), 0).rgb;
    vec3 c010 = texelFetch(lut, i0 + ivec3(0, 1, 0), 0).rgb;
    vec3 c001 = texelFetch(lut, i0 + ivec3(0, 0, 1), 0).rgb;
    vec3 c110 = texelFetch(lut, i0 + ivec3(1, 1, 0), 0).rgb;
    vec3 c101 = texelFetch(lut, i0 + ivec3(1, 0, 1), 0).rgb;
    vec3 c011 = texelFetch(lut, i0 + ivec3(0, 1, 1), 0).rgb;
    vec3 c111 = texelFetch(lut, i0 + ivec3(1, 1, 1), 0).rgb;

    if (interp == 1) {
        vec3 c00 = mix(c000, c100, f.r);
        vec3 c10 = mix(c010, c110, f.r);
        vec3 c01 = mix(c001, c101, f.r);
        vec3 c11 = mix(c011, c111, f.r);
        vec3 c0  = mix(c00, c10, f.g);
        vec3 c1  = mix(c01, c11, f.g);
        return mix(c0, c1, f.b);
    }

    /* 四面体插值：与 Resolve / OCIO 一致，误差显著低于三线性 */
    if (f.r > f.g) {
        if (f.g > f.b) {            // r > g > b
            return c000 + f.r * (c100 - c000) + f.g * (c110 - c100) + f.b * (c111 - c110);
        } else if (f.r > f.b) {     // r > b >= g
            return c000 + f.r * (c100 - c000) + f.b * (c101 - c100) + f.g * (c111 - c101);
        } else {                    // b >= r > g
            return c000 + f.b * (c001 - c000) + f.r * (c101 - c001) + f.g * (c111 - c101);
        }
    } else {
        if (f.b > f.g) {            // b > g >= r
            return c000 + f.b * (c001 - c000) + f.g * (c011 - c001) + f.r * (c111 - c011);
        } else if (f.b > f.r) {     // g >= b > r
            return c000 + f.g * (c010 - c000) + f.b * (c011 - c010) + f.r * (c111 - c011);
        } else {                    // g >= r >= b
            return c000 + f.g * (c010 - c000) + f.r * (c110 - c010) + f.b * (c111 - c110);
        }
    }
}

void main() {
    vec3 logColor = texture(uLogTexture, vTexCoord).rgb;

    vec3 display709 = sampleLut3D(uRestoreLut, uRestoreSize,
                                  uRestoreDomainMin, uRestoreDomainMax, logColor, uInterp);

    vec3 linear709 = decodeToLinear(display709);

    vec3 linearAwg3 = (uGamutConvert == 1) ? (uM709ToAwg3 * linear709) : linear709;

    vec3 logc3 = vec3(logc3Encode(linearAwg3.r),
                      logc3Encode(linearAwg3.g),
                      logc3Encode(linearAwg3.b));

    vec3 outColor = sampleLut3D(uLookLut, uLookSize,
                                uLookDomainMin, uLookDomainMax, logc3, uInterp);

    if (uClipOutput == 1) {
        outColor = clamp(outColor, 0.0, 1.0);
    }
    fragColor = vec4(outColor, 1.0);
}
