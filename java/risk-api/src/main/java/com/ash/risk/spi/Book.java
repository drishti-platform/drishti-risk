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

/**
 * A measure's loaded state for one date: immutable once built, shared by every reader, keyed by the visibility signature for anything
 * derived from a request. The kernel keeps it in the day's session.
 */
public interface Book {

    /** Bytes held: heap, direct and mapped. */
    long memoryBytes();
}
