package com.obsinity.telemetry.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;

@JsonInclude(Include.NON_NULL)
public final class OResource {
	private final OAttributes attributes;

	public OResource(OAttributes attributes) {
		this.attributes = attributes;
	}

	public OAttributes attributes() {
		return attributes;
	}

	public Resource toOtel() {
		return Resource.create(attributes != null ? attributes.toOtel() : Attributes.empty());
	}

	public static OResource fromOtel(Resource r) {
		return new OResource(OAttributes.fromOtel(r == null ? Attributes.empty() : r.getAttributes()));
	}
}
