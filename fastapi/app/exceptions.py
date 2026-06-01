from __future__ import annotations


class ChzzkSessionException(Exception):
    def __init__(
        self,
        *,
        status_code: int,
        code: str,
        message: str,
        broadcast_stream_id: str | None = None,
        attempt_id: str | None = None,
    ) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message
        self.broadcast_stream_id = broadcast_stream_id
        self.attempt_id = attempt_id
