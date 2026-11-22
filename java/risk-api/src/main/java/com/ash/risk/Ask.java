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

/** The parts of an answer a question asks for (design 4.1). */
public enum Ask {
    /** The scope's figures. */
    HEADLINE,
    /** Each member's contribution (component, incremental, marginal where the measure has them). */
    CONTRIBUTIONS,
    /** The scope's scenario path (a vector), asked explicitly. */
    PATH,
    /** The figure over past dates. */
    HISTORY,
    /** Use of each limit that binds the figure, with its status. */
    LIMITS
}
