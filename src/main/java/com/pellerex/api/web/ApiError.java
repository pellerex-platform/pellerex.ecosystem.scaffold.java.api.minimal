package com.pellerex.api.web;

import java.util.List;

/**
 * Generic error envelope returned to clients. Carries no stack trace, exception type, or internal
 * detail (JV-D21 / JV-R15).
 */
public record ApiError(int status, String error, String message, List<String> details) {
}
