FROM node:22-bookworm-slim

# Claude Code CLI + python runtime for the daemon.
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 python3-venv python3-dev \
        ca-certificates curl git ripgrep \
    && rm -rf /var/lib/apt/lists/*

RUN npm install -g @anthropic-ai/claude-code && npm cache clean --force

# The container runs as uid 1000 (compose `user:`) so the bind-mounted host
# ~/.claude stays writable. uid 1000 already exists in the node image as `node`,
# so just give it a home directory to use.
RUN mkdir -p /home/app && chown 1000:1000 /home/app

ENV VIRTUAL_ENV=/opt/venv
RUN python3 -m venv "$VIRTUAL_ENV"
ENV PATH="$VIRTUAL_ENV/bin:$PATH"

COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt

COPY app /srv/app
COPY entrypoint.sh /srv/entrypoint.sh
RUN chmod +x /srv/entrypoint.sh

# CLAUDE_CONFIG_DIR is deliberately NOT set: with it, the CLI expects .claude.json
# *inside* the config dir, which here is the shared host mount. Leaving it unset
# keeps the credentials shared (via ~/.claude) and .claude.json container-local.
ENV HOME=/home/app \
    PYTHONUNBUFFERED=1 \
    MACROPAD_DATA_DIR=/data

WORKDIR /srv
EXPOSE 8321

ENTRYPOINT ["/srv/entrypoint.sh"]
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8321"]
