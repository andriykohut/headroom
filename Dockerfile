# The relay, and nothing else. It makes no outbound requests, so it needs no
# CA bundle; it starts no subprocess, so it needs no shell. What ships is one
# static binary, which is also the smallest attack surface available to a
# service exposed to the internet.
#
# The binary is not built here. It arrives from the release matrix, which
# builds it natively — see .github/workflows/release.yml.
FROM scratch

ARG TARGETARCH
COPY dist/headroom-linux-${TARGETARCH} /headroom

# The binary derives its state path from HOME, and scratch has none set. Left
# unset, it falls back to the temp directory, and the relay's "a restart does
# not blank your phone" promise silently stops being true. There is also no
# /etc/passwd here to look HOME up in, so it must be set directly.
ENV HOME=/data
VOLUME /data

# Not root. The systemd unit in the README already runs the relay under
# DynamicUser; an internet-facing image should not be laxer than the
# documented bare-metal setup. A numeric uid needs no /etc/passwd entry,
# which scratch has not got.
USER 65534:65534

# The binary defaults to loopback because it speaks plain HTTP and should not
# be reachable before someone puts TLS in front of it. Inside a container that
# default is unreachable from anywhere, including the reverse proxy, so the
# image changes it — through the environment rather than through CMD, because
# an operator's own arguments replace CMD and would silently drop a flag.
ENV HEADROOM_HOST=0.0.0.0
EXPOSE 8765

ENTRYPOINT ["/headroom"]
CMD ["serve"]
