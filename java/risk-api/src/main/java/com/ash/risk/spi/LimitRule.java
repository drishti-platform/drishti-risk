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
package com.ash.risk.spi;

/** Which limit kind binds which figure at which node, and the one status rule for every measure (design 2.1 Limits). */
public interface LimitRule {

    /** The status of a figure against its limit. */
    enum Status { WITHIN, NEAR, BREACH }

    /** The measure and the limit contract this rule binds ({@code var} to {@code risk/var-limit@1}). */
    String measure();

    String limitContract();

    /** The share of the limit used ({@code |figure| / limit}). */
    default double use(double figure, double limit) {
        return Math.abs(figure) / limit;
    }

    /** The status for a use; {@code near} is the module's threshold (a share such as 0.9). */
    default Status status(double use, double near) {
        return use > 1 ? Status.BREACH : use >= near ? Status.NEAR : Status.WITHIN;
    }
}
