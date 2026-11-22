/*
 * Drishti Risk · market and counterparty risk as engines.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.risk;

import com.ash.engine.EngineException;
import java.util.Map;

/**
 * A refusal or failure of the product with an {@code RSK-} code (RK-2) and words a person may read. In a host it travels as the engine
 * API's {@link EngineException} with the same code and kind ({@link #toEngine}); the host maps the family once (Drishti: to {@code DRS-}).
 * An engine API code ({@code ENG-}) is used where one says the same fact.
 */
public final class RiskException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final EngineException.Kind kind;

    public RiskException(String code, EngineException.Kind kind, String message) {
        this(code, kind, message, null);
    }

    public RiskException(String code, EngineException.Kind kind, String message, Throwable cause) {
        super(message, cause);
        if (code == null || !code.matches("(RSK|ENG)-\\d{4}")) {
            throw new IllegalArgumentException("a code is RSK-nnnn or ENG-nnnn: " + code);
        }
        this.code = code;
        this.kind = kind;
    }

    /** From the engine API's exception (the same code and kind). */
    public static RiskException of(EngineException e) {
        return new RiskException(e.code(), e.kind(), e.getMessage(), e);
    }

    public String code() {
        return code;
    }

    public EngineException.Kind kind() {
        return kind;
    }

    /** The same refusal as the engine API carries it. */
    public EngineException toEngine() {
        return new EngineException(code, kind, getMessage(), Map.of(), this);
    }
}
