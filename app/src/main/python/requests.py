"""Minimal pure-Python `requests` replacement for the Android build.

The reused FunSportWorld core calls a small, well-defined subset of requests:
sessions with headers, JSON POST/GET/PUT, query parameters, timeouts and
`raise_for_status`. Shipping the real requests package would pull five pip
dependencies through Chaquopy's resolver; this module implements that subset
on top of `urllib.request` and keeps the APK build fast and deterministic.

Only the following API is provided (enough for the copied core):

* Session: headers, request(), get(), post(), put(), close()
* module functions get/post/put/request
* Response: status_code, content, text, json(), raise_for_status(),
  headers, url, ok, context-manager support
* exceptions: RequestException, HTTPError, ConnectionError, Timeout
"""

import gzip
import json as _json
import socket
import ssl
import urllib.error
import urllib.parse
import urllib.request

__version__ = "2.31.0-android-shim"

DEFAULT_USER_AGENT = "python-requests/2.31.0"


class RequestException(IOError):
    """Base exception, matching requests' hierarchy closely enough for callers."""

    def __init__(self, *args, response=None, request=None):
        super().__init__(*args)
        self.response = response
        self.request = request


class HTTPError(RequestException):
    def __init__(self, *args, response=None, request=None):
        super().__init__(*args, response=response, request=request)
        self.response = response


class ConnectionError(RequestException):
    pass


class Timeout(RequestException):
    pass


class ReadTimeout(Timeout):
    pass


def _normalize_url(url, params=None):
    if params:
        query = urllib.parse.urlencode(params, doseq=True)
        separator = "&" if "?" in url else "?"
        url = url + separator + query
    return url


def _decode_text(content, headers):
    charset = "utf-8"
    content_type = ""
    for key, value in headers.items():
        if key.lower() == "content-type":
            content_type = value or ""
    for part in content_type.split(";"):
        part = part.strip()
        if part.lower().startswith("charset="):
            charset = part.split("=", 1)[1].strip().strip('"') or "utf-8"
    try:
        return content.decode(charset, errors="replace")
    except LookupError:
        return content.decode("utf-8", errors="replace")


class Response:
    def __init__(self, status_code, content, url, headers, reason=""):
        self.status_code = int(status_code)
        self.content = bytes(content or b"")
        self.url = url
        self.headers = dict(headers or {})
        self.reason = reason or ""
        self.encoding = "utf-8"

    @property
    def text(self):
        return _decode_text(self.content, self.headers)

    @property
    def ok(self):
        return self.status_code < 400

    def json(self, **kwargs):
        return _json.loads(self.text, **kwargs)

    def raise_for_status(self):
        if self.status_code >= 400:
            raise HTTPError(
                "{} Client Error: {} for url: {}".format(self.status_code, self.reason, self.url),
                response=self,
            )

    def close(self):
        pass

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False

    def __repr__(self):
        return "<Response [{}]>".format(self.status_code)


def _build_headers(default_headers, headers):
    merged = dict(default_headers or {})
    if headers:
        merged.update(headers)
    merged.setdefault("User-Agent", DEFAULT_USER_AGENT)
    # Avoid transparent gzip so Content-Length / bytes stay predictable.
    merged.setdefault("Accept-Encoding", "identity")
    return merged


def _timeout_value(timeout):
    if timeout is None:
        return None
    if isinstance(timeout, (tuple, list)):
        values = [float(item) for item in timeout if item is not None]
        return max(values) if values else None
    return float(timeout)


def _open(request, timeout):
    try:
        with urllib.request.urlopen(request, timeout=timeout) as raw:
            content = raw.read()
            headers = dict(raw.headers.items())
            if content[:2] == b"\x1f\x8b":
                content = gzip.decompress(content)
            return Response(raw.status, content, raw.geturl(), headers, raw.reason)
    except urllib.error.HTTPError as error:
        content = b""
        try:
            content = error.read()
            if content[:2] == b"\x1f\x8b":
                content = gzip.decompress(content)
        except Exception:
            content = b""
        headers = dict(error.headers.items()) if error.headers else {}
        return Response(error.code, content, request.full_url, headers, error.reason)
    except (socket.timeout, TimeoutError) as exc:
        raise ReadTimeout(str(exc)) from None
    except urllib.error.URLError as exc:
        reason = getattr(exc, "reason", exc)
        if isinstance(reason, (socket.timeout, TimeoutError)):
            raise ReadTimeout(str(reason)) from None
        raise ConnectionError(str(reason)) from None
    except ssl.SSLError as exc:
        raise ConnectionError("SSL error: {}".format(exc)) from None


class Session:
    def __init__(self):
        self.headers = {}
        self.cookies = {}
        self._closed = False

    def request(self, method, url, data=None, headers=None, timeout=None, params=None, **kwargs):
        url = _normalize_url(url, params)
        body = data
        if isinstance(body, str):
            body = body.encode("utf-8")
        elif isinstance(body, dict) or isinstance(body, (list, tuple)):
            body = urllib.parse.urlencode(body, doseq=True).encode("utf-8")
        request = urllib.request.Request(
            url,
            data=body,
            headers=_build_headers(self.headers, headers),
            method=str(method).upper(),
        )
        return _open(request, _timeout_value(timeout))

    def get(self, url, **kwargs):
        return self.request("GET", url, **kwargs)

    def post(self, url, data=None, json=None, **kwargs):
        if json is not None:
            data = _json.dumps(json).encode("utf-8")
            headers = dict(kwargs.get("headers") or {})
            headers.setdefault("Content-Type", "application/json")
            kwargs["headers"] = headers
        return self.request("POST", url, data=data, **kwargs)

    def put(self, url, data=None, **kwargs):
        return self.request("PUT", url, data=data, **kwargs)

    def head(self, url, **kwargs):
        return self.request("HEAD", url, **kwargs)

    def close(self):
        self._closed = True

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        self.close()
        return False


def request(method, url, **kwargs):
    session = Session()
    try:
        return session.request(method, url, **kwargs)
    finally:
        pass


def get(url, params=None, **kwargs):
    return Session().get(url, params=params, **kwargs)


def post(url, data=None, json=None, **kwargs):
    return Session().post(url, data=data, json=json, **kwargs)


def put(url, data=None, **kwargs):
    return Session().put(url, data=data, **kwargs)
