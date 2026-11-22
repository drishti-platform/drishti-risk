/*
 * Project Drishti · Any data. Any domain. One grammar.
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
package com.ash.risk.kernel;

import com.ash.engine.EngineException;
import java.util.Map;

/**
 * A refusal or failure inside the moved code, unchecked, always with a {@link RiskCode} (it replaces Drishti's {@code RiskFailure},
 * step K4). An engine adapter turns it into the engine API's {@link EngineException} with the same code and kind ({@link #toEngine}); the
 * checked {@code com.ash.risk.RiskException} of the facade carries the same pair.
 */
public class RiskFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final transient RiskCode code;

    public RiskFailure(RiskCode code, String message) {
        super(message);
        this.code = code;
    }

    public RiskFailure(RiskCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** The code this failure carries. */
    public RiskCode errorCode() {
        return code;
    }

    /** The code, a space and the message. */
    @Override
    public String getMessage() {
        return code.code() + " " + super.getMessage();
    }

    /** The same refusal as the engine API carries it. */
    public EngineException toEngine() {
        return new EngineException(code.code(), code.kind(), super.getMessage(), Map.of(), this);
    }
}
