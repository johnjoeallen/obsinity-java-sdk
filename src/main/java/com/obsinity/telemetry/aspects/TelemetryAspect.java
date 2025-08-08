package com.obsinity.telemetry.aspects;

import com.obsinity.telemetry.aspects.processors.TelemetryProcessor;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@RequiredArgsConstructor
@Component
public class TelemetryAspect<T> {

	private final TelemetryProcessor<T> telemetryProcessor;

	@Around("execution(@com.obsinity.telemetry.annotations.Flow * *(..))")
	public Object interceptFlow(ProceedingJoinPoint joinPoint) throws Throwable { // NOSONAR
		return telemetryProcessor.proceed(joinPoint);
	}

	@Around("execution(@com.obsinity.telemetry.annotations.Step * *(..))")
	public Object interceptStep(ProceedingJoinPoint joinPoint) throws Throwable { // NOSONAR
		return telemetryProcessor.proceed(joinPoint);
	}

}
