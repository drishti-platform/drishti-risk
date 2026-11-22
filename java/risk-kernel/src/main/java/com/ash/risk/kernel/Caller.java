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

/**
 * Who asks, as the product sees it (it replaces Drishti's {@code Caller}, step K4): the actor's name, for the audit line only, and the
 * signature of the visibility the host decided for the request (the rows the caller may see). The product never holds a role nor decides
 * an access: the host has applied its rules before the request reaches the engine (ENGINE_SPEC 8.6); state derived from a request is kept
 * per {@link #visibility}.
 *
 * @param user the actor's name (never part of an answer)
 * @param visibility the request's visibility signature
 */
public record Caller(String user, String visibility) {

    public Caller {
        user = user == null || user.isBlank() ? "anonymous" : user;
        visibility = visibility == null ? "" : visibility;
    }

    /** A caller whose visibility the host has not narrowed (tests, offline tools). */
    public static Caller of(String user) {
        return new Caller(user, "");
    }
}
