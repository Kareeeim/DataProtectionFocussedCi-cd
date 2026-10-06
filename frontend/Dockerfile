# ===========================================================================
#  DemoBank - frontend image
# ===========================================================================
#  Multi-stage build. The Node toolchain, the npm cache and the full source
#  tree stay in the build stage; the runtime image contains nothing but the
#  compiled static files and nginx. A smaller image is not just a matter of
#  tidiness - every package that is not present is a package that cannot be
#  used to attack the container.
# ===========================================================================

# ---------------------------------------------------------------------------
#  Stage 1 - compile the Angular application
# ---------------------------------------------------------------------------
FROM node:22-alpine AS build

WORKDIR /build

# Manifests first: this layer is rebuilt only when a dependency changes, so an
# ordinary source edit reuses the cached `npm ci` layer instead of reinstalling
# everything.
COPY package.json package-lock.json ./

# `npm ci` installs exactly what the lockfile pins and fails if the two files
# disagree. Unlike `npm install`, it can never silently resolve a different
# version than the one that was reviewed.
RUN npm ci --no-audit --no-fund

COPY . .

# Production build: ahead-of-time compilation, tree shaking, minification and
# content hashing of every emitted file.
RUN npm run build -- --configuration production


# ---------------------------------------------------------------------------
#  Stage 2 - serve the compiled files
# ---------------------------------------------------------------------------
# The unprivileged variant runs as a non-root user out of the box and is built
# to listen on 8080, so no capability has to be handed back to the process.
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime

# Switch to root only for the duration of the install and the file copies, then
# drop straight back to the unprivileged user.
USER root

# Pull in the latest patched packages from the Alpine branch.
RUN apk --no-cache upgrade

# Server configuration. The snippets live outside conf.d because anything in
# that directory is parsed as a top-level server config, not as something that
# can be included inside a location block.
COPY nginx/default.conf           /etc/nginx/conf.d/default.conf
COPY nginx/proxy.conf             /etc/nginx/snippets/proxy.conf
COPY nginx/security-headers.conf  /etc/nginx/snippets/security-headers.conf
COPY nginx/api-headers.conf       /etc/nginx/snippets/api-headers.conf

# The compiled application. The Angular application builder writes the browser
# bundle under `browser/`; only that directory is served.
COPY --from=build /build/dist/frontend/browser /usr/share/nginx/html

# Source maps would hand a reader the original TypeScript for free. The
# production build does not emit them, but this makes that guarantee explicit
# rather than dependent on a build-flag default.
RUN find /usr/share/nginx/html -name '*.map' -delete

# uid 101 is the `nginx` user that ships with the unprivileged image.
USER 101

EXPOSE 8080

# Container-level probe, so the image is usable outside compose as well.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q -O- http://127.0.0.1:8080/healthz || exit 1

CMD ["nginx", "-g", "daemon off;"]
