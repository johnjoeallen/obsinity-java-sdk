package com.obsinity.telemetry.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.StatusData;

@JsonInclude(Include.NON_NULL)
public final class OStatus {
	private final StatusCode code;
	private final String message;

	public OStatus(StatusCode code, String message) {
		this.code = code;
		this.message = message;
	}

	public StatusCode code() {
		return code;
	}

	public String message() {
		return message;
	}

	public StatusData toOtel() {
		return StatusData.create(code, message);
	}

	public static OStatus fromOtel(StatusData sd) {
		if (sd == null) return null;
		return new OStatus(sd.getStatusCode(), sd.getDescription());
	}
}
