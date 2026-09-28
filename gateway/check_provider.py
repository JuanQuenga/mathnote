"""Check the public OAuth discovery facts needed by the MathNote MCP gateway.

This cannot prove that the provider echoes OAuth `resource` into JWT `aud`;
that must be tested with a real token from the selected provider.
"""
import json
import os
import sys
import urllib.request
from urllib.parse import urlparse

from mcp_gateway import Config


def fetch_json(url):
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=8) as response:
        if response.status != 200:
            raise RuntimeError(f"HTTP {response.status} from {url}")
        return json.load(response)


def validate_provider(metadata, config):
    errors = []
    if metadata.get("issuer") != config.issuer:
        errors.append("provider issuer does not exactly match MATHNOTE_OAUTH_ISSUER")
    for field in ("authorization_endpoint", "token_endpoint", "jwks_uri"):
        value = metadata.get(field, "")
        if urlparse(value).scheme != "https":
            errors.append(f"{field} is missing or not HTTPS")
    if metadata.get("jwks_uri") != config.jwks_url:
        errors.append("provider jwks_uri does not exactly match MATHNOTE_OAUTH_JWKS_URL")
    if "S256" not in metadata.get("code_challenge_methods_supported", []):
        errors.append("provider does not advertise PKCE S256")
    if not metadata.get("client_id_metadata_document_supported") and not metadata.get("registration_endpoint"):
        errors.append("provider advertises neither CIMD nor dynamic client registration")
    return errors


def main():
    try:
        config = Config.from_env()
        metadata_url = os.environ.get("MATHNOTE_OAUTH_METADATA_URL", "")
        if not metadata_url or urlparse(metadata_url).scheme != "https":
            raise ValueError("set MATHNOTE_OAUTH_METADATA_URL to your provider's HTTPS discovery URL")
        provider = fetch_json(metadata_url)
        errors = validate_provider(provider, config)
        resource_url = config.public_url.replace("/mcp", "/.well-known/oauth-protected-resource/mcp")
        resource = fetch_json(resource_url)
        if resource.get("resource") != config.public_url:
            errors.append("public protected-resource metadata has the wrong resource URL")
        if config.issuer not in resource.get("authorization_servers", []):
            errors.append("public protected-resource metadata does not list the configured issuer")
        if errors:
            for error in errors:
                print("FAIL:", error, file=sys.stderr)
            return 1
        print("OAuth discovery and protected-resource metadata: OK")
        print("Still verify a real token's aud, scopes, and owner subject with this provider.")
        return 0
    except (ValueError, OSError, json.JSONDecodeError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
