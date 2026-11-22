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
package com.ash.risk.market.engine;

import com.ash.engine.Answer;
import com.ash.engine.Table;
import com.ash.risk.kernel.vector.ParallelSums;
import com.ash.risk.market.calc.VarBook;
import com.ash.risk.market.calc.VarCalc;
import com.ash.risk.market.calc.VarMath;
import com.ash.tools.xlsx.Sheet;
import com.ash.tools.xlsx.Workbook;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The formula workbook of a market-risk answer: {@code Data} holds, values only, each scenario's index and date and each node's
 * aggregate P&amp;L in USD or the reporting currency; {@code Main} recomputes every node's VaR (the k-th smallest P&amp;L, negated; the
 * interpolated rule between two ranks) and ES (minus the mean of the k smallest) with formulas over {@code Data}, the engine's values
 * stored beside them. Written with the family's one .xlsx writer: the same bytes for the same request.
 */
final class MarketWorkbook {

    private MarketWorkbook() {
    }

    static void write(OutputStream out, MarketBooks.Built built, MarketOptions options, ParallelSums sums, Answer answer) {
        try (Workbook wb = Workbook.to(out)) {
            Sheet data = wb.sheet("Data");
            if (built == null) {
                data.row(1).text(0, "No data", "header");
                wb.sheet("Main").row(1).text(0, "No answer: " + String.join("; ", answer.missing().stream().map(m -> m.words()).toList()), "normal");
                return;
            }
            VarBook b = built.book();
            VarCalc calc = options.calc();
            List<VarBook.Node> nodes = new ArrayList<>(b.nodes().values());
            int n = b.width();
            var header = data.row(1).text(0, "SCENARIO", "header").text(1, "DATE", "header");
            for (int c = 0; c < nodes.size(); c++) {
                header.text(c + 2, nodes.get(c).id(), "header");
            }
            List<double[]> vectors = new ArrayList<>();
            for (VarBook.Node x : nodes) {
                vectors.add(b.aggregate(x.id()));
            }
            for (int j = 0; j < n; j++) {
                var r = data.next().number(0, j, "int").text(1, built.scenarios().get(j).toString(), "normal");
                for (int c = 0; c < nodes.size(); c++) {
                    r.number(c + 2, vectors.get(c)[j], "decimal");
                }
            }
            Sheet main = wb.sheet("Main");                               // Data is complete before Main starts (the writer streams)
            int k = VarMath.rank(n, calc.confidence());
            int kes = VarMath.rank(n, calc.esConfidence());
            main.row(1).text(0, "VaR confidence", "bold").number(1, calc.confidence(), "decimal");
            main.row(2).text(0, "ES confidence", "bold").number(1, calc.esConfidence(), "decimal");
            main.row(3).text(0, "Scenarios", "bold").number(1, n, "int");
            main.row(4).text(0, "k (VaR)", "bold").formula(1, "MAX(1,MIN(B3,CEILING(B3*(1-B1)-1E-9,1)))", (double) k, "int");
            main.row(5).text(0, "k (ES)", "bold").formula(1, "MAX(1,MIN(B3,CEILING(B3*(1-B2)-1E-9,1)))", (double) kes, "int");
            main.row(7).text(0, "NODE_ID", "header").text(1, "VAR", "header").text(2, "ES", "header");
            int row = 7;
            for (int c = 0; c < nodes.size(); c++) {
                row++;
                String col = letters(c + 2);
                String range = "Data!$" + col + "$2:$" + col + "$" + (n + 1);
                VarCalc.Measures ms = calc.measures(vectors.get(c));
                String var;
                if (calc.rule() == VarMath.RankRule.INTERPOLATED) {
                    double pos = n * (1.0 - calc.confidence());
                    int lo = Math.max(1, (int) Math.floor(pos));
                    int hi = Math.min(n, lo + 1);
                    double w = Math.min(1.0, Math.max(0.0, pos - lo));
                    var = "-((1-" + w + ")*SMALL(" + range + "," + lo + ")+" + w + "*SMALL(" + range + "," + hi + "))";
                } else {
                    var = "-SMALL(" + range + ",$B$4)";
                }
                String es = "-SUMPRODUCT(SMALL(" + range + ",ROW($1:$" + kes + ")))/$B$5";
                main.row(row).text(0, nodes.get(c).id(), "normal").formula(1, var, ms.var(), "decimal").formula(2, es, ms.es(), "decimal");
            }
            Table t = answer.table("headline");
            if (t != null && t.rows() > 0) {
                wb.name("OUT_HEADLINE_VAR", main.name(), "$B$8");
            }
        }
    }

    /** A zero-based column index as spreadsheet letters (0: A, 25: Z, 26: AA). */
    static String letters(int index) {
        StringBuilder s = new StringBuilder();
        int i = index + 1;
        while (i > 0) {
            int m = (i - 1) % 26;
            s.insert(0, (char) ('A' + m));
            i = (i - 1) / 26;
        }
        return s.toString();
    }
}
