/* Minimal CPU renderer for 3D Gaussian splats: project, sort front to back, alpha-composite. */
#include <math.h>
#include <stdlib.h>
#include <string.h>

static const float *g_depth;
static int cmp(const void *a, const void *b) {
    float da = g_depth[*(const int *)a], db = g_depth[*(const int *)b];
    return da < db ? -1 : da > db;
}

static void sh_color(const float *dc, const float *sh, int ncoef, float x, float y, float z, float *out) {
    const float C0 = 0.28209479177387814f, C1 = 0.4886025119029199f;
    const float C2[5] = {1.0925484305920792f, -1.0925484305920792f, 0.31539156525252005f, -1.0925484305920792f, 0.5462742152960396f};
    const float C3[7] = {-0.5900435899266435f, 2.890611442640554f, -0.4570457994644658f, 0.3731763325901154f,
                         -0.4570457994644658f, 1.445305721320277f, -0.5900435899266435f};
    float xx = x * x, yy = y * y, zz = z * z, xy = x * y, yz = y * z, xz = x * z;
    float basis[15] = {
        -C1 * y, C1 * z, -C1 * x,
        C2[0] * xy, C2[1] * yz, C2[2] * (2 * zz - xx - yy), C2[3] * xz, C2[4] * (xx - yy),
        C3[0] * y * (3 * xx - yy), C3[1] * xy * z, C3[2] * y * (4 * zz - xx - yy), C3[3] * z * (2 * zz - 3 * xx - 3 * yy),
        C3[4] * x * (4 * zz - xx - yy), C3[5] * z * (xx - yy), C3[6] * x * (xx - 3 * yy)};
    for (int c = 0; c < 3; c++) {
        float v = C0 * dc[c] + 0.5f;
        for (int k = 0; k < ncoef; k++) v += basis[k] * sh[k * 3 + c];
        out[c] = v < 0 ? 0 : v;
    }
}

/* cam: eye[3], right[3], up[3], fwd[3], fx, fy, cx, cy. out: w*h*4 floats (premultiplied rgb, alpha). */
void render(int n, const float *pos, const float *dc, const float *sh, int ncoef, const float *opacity,
            const float *scale, const float *quat, const float *cam, int w, int h, float *out) {
    const float *eye = cam, *R = cam + 3, *U = cam + 6, *F = cam + 9;
    float fx = cam[12], fy = cam[13], cx = cam[14], cy = cam[15];
    float *depth = malloc(sizeof(float) * n);
    float *mx = malloc(sizeof(float) * n), *my = malloc(sizeof(float) * n);
    float *con = malloc(sizeof(float) * n * 4);
    float *col = malloc(sizeof(float) * n * 3);
    int *rad = malloc(sizeof(int) * n);
    int *order = malloc(sizeof(int) * n);
    int m = 0;
    for (int i = 0; i < n; i++) {
        float d[3] = {pos[i * 3] - eye[0], pos[i * 3 + 1] - eye[1], pos[i * 3 + 2] - eye[2]};
        float tx = d[0] * R[0] + d[1] * R[1] + d[2] * R[2];
        float ty = -(d[0] * U[0] + d[1] * U[1] + d[2] * U[2]);
        float tz = d[0] * F[0] + d[1] * F[1] + d[2] * F[2];
        if (tz < 0.2f) continue;
        /* Rotation from quaternion (x, y, z, w). */
        const float *q = quat + i * 4;
        float qn = sqrtf(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        float x = q[0] / qn, y = q[1] / qn, z = q[2] / qn, r = q[3] / qn;
        float Rm[9] = {1 - 2 * (y * y + z * z), 2 * (x * y - r * z), 2 * (x * z + r * y),
                       2 * (x * y + r * z), 1 - 2 * (x * x + z * z), 2 * (y * z - r * x),
                       2 * (x * z - r * y), 2 * (y * z + r * x), 1 - 2 * (x * x + y * y)};
        float s[3] = {scale[i * 3], scale[i * 3 + 1], scale[i * 3 + 2]};
        float M[9];
        for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) M[a * 3 + b] = Rm[a * 3 + b] * s[b];
        float S[9];
        for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
            S[a * 3 + b] = 0;
            for (int k = 0; k < 3; k++) S[a * 3 + b] += M[a * 3 + k] * M[b * 3 + k];
        }
        float W[9] = {R[0], R[1], R[2], -U[0], -U[1], -U[2], F[0], F[1], F[2]};
        float J[6] = {fx / tz, 0, -fx * tx / (tz * tz), 0, fy / tz, -fy * ty / (tz * tz)};
        float T[6];
        for (int a = 0; a < 2; a++) for (int b = 0; b < 3; b++) {
            T[a * 3 + b] = 0;
            for (int k = 0; k < 3; k++) T[a * 3 + b] += J[a * 3 + k] * W[k * 3 + b];
        }
        float TS[6];
        for (int a = 0; a < 2; a++) for (int b = 0; b < 3; b++) {
            TS[a * 3 + b] = 0;
            for (int k = 0; k < 3; k++) TS[a * 3 + b] += T[a * 3 + k] * S[k * 3 + b];
        }
        float c00 = 0, c01 = 0, c11 = 0;
        for (int k = 0; k < 3; k++) {
            c00 += TS[k] * T[k];
            c01 += TS[k] * T[3 + k];
            c11 += TS[3 + k] * T[3 + k];
        }
        float det0 = c00 * c11 - c01 * c01;
        c00 += 0.3f;
        c11 += 0.3f;
        float det = c00 * c11 - c01 * c01;
        if (det <= 0) continue;
        float op = opacity[i] * sqrtf(fmaxf(det0, 0) / det);
        if (op < 1.0f / 255) continue;
        float mid = 0.5f * (c00 + c11);
        float lam = mid + sqrtf(fmaxf(0.1f, mid * mid - det));
        int radius = (int)ceilf(3 * sqrtf(lam));
        float px = fx * tx / tz + cx, py = fy * ty / tz + cy;
        if (px + radius < 0 || px - radius >= w || py + radius < 0 || py - radius >= h) continue;
        float len = sqrtf(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        sh_color(dc + i * 3, sh + (size_t)i * ncoef * 3, ncoef, d[0] / len, d[1] / len, d[2] / len, col + i * 3);
        depth[i] = tz;
        mx[i] = px;
        my[i] = py;
        con[i * 4] = c11 / det;
        con[i * 4 + 1] = -c01 / det;
        con[i * 4 + 2] = c00 / det;
        con[i * 4 + 3] = op;
        rad[i] = radius;
        order[m++] = i;
    }
    g_depth = depth;
    qsort(order, m, sizeof(int), cmp);
    float *Tr = malloc(sizeof(float) * w * h);
    for (int p = 0; p < w * h; p++) Tr[p] = 1;
    memset(out, 0, sizeof(float) * w * h * 4);
    for (int o = 0; o < m; o++) {
        int i = order[o];
        int x0 = (int)fmaxf(0, mx[i] - rad[i]), x1 = (int)fminf(w - 1, mx[i] + rad[i]);
        int y0 = (int)fmaxf(0, my[i] - rad[i]), y1 = (int)fminf(h - 1, my[i] + rad[i]);
        const float *k = con + i * 4;
        for (int y = y0; y <= y1; y++) {
            float dy = y + 0.5f - my[i];
            for (int x = x0; x <= x1; x++) {
                int p = y * w + x;
                float t = Tr[p];
                if (t < 1e-4f) continue;
                float dx = x + 0.5f - mx[i];
                float power = -0.5f * (k[0] * dx * dx + k[2] * dy * dy) - k[1] * dx * dy;
                if (power > 0) continue;
                float a = fminf(0.99f, k[3] * expf(power));
                if (a < 1.0f / 255) continue;
                out[p * 4] += t * a * col[i * 3];
                out[p * 4 + 1] += t * a * col[i * 3 + 1];
                out[p * 4 + 2] += t * a * col[i * 3 + 2];
                Tr[p] = t * (1 - a);
            }
        }
    }
    for (int p = 0; p < w * h; p++) out[p * 4 + 3] = 1 - Tr[p];
    free(Tr); free(depth); free(mx); free(my); free(con); free(col); free(rad); free(order);
}
