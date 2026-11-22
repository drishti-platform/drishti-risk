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

import com.ash.engine.Answer;
import com.ash.engine.Explanation;
import com.ash.engine.Cell;
import com.ash.engine.Visibility;
import java.io.OutputStream;

/**
 * A question to one measure of a day (design 4.1): a scope, the caller's visibility (decided by the host; no principal ever reaches the
 * product), a lens, method switches; then the parts asked. Immutable: each call returns a new query.
 */
public interface MeasureQuery {

    MeasureQuery scope(Scope scope);

    /** The rows the answer may be computed from. Without one the question is refused ({@code ENG-4001}). */
    MeasureQuery visibility(Visibility visibility);

    MeasureQuery lens(Lens lens);

    /** One named method switch ({@code rank-rule}: {@code ceil}); the alternative is computed beside it where the module says so. */
    MeasureQuery method(String name, String value);

    /** The actor's name, for the audit line only. */
    MeasureQuery actor(String actor);

    /** The tables of the parts asked; never a vector per part unless {@link Ask#PATH} is asked. */
    Answer ask(Ask... parts) throws RiskException;

    /** The derivation of one figure of the answer, as data. */
    Explanation explain(Cell cell) throws RiskException;

    /** The formula workbook of the answer: a values-only Data sheet, formula sheets, a drop-down per method switch. */
    void workbook(OutputStream out) throws RiskException;
}
