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
package com.ash.risk.market.engine;

import com.ash.risk.kernel.workbook.AnswerBook;
import com.ash.risk.market.calc.VarBook;
import com.ash.risk.market.calc.VarCalc;
import com.ash.risk.market.calc.VarMath;
import com.ash.tools.xlsx.Sheet;
import com.ash.tools.xlsx.Workbook;
import java.util.ArrayList;
import java.util.List;

/**
 * The historical VaR part of a market-risk workbook (node-var, node-components, basket-var, headline): its {@code Data} block holds, values
 * only, each scenario's index and date and each node's aggregate P&amp;L in USD or the reporting currency; its {@code Main} section
 * recomputes every node's VaR (the k-th smallest P&amp;L, negated; the interpolated rule between two ranks) and ES (minus the mean of the
 * k smallest) with formulas over the block, the engine's values cached beside them.
 */
final class MarketWorkbook implements AnswerBook.Part {

    private final MarketBooks.Built built;
    private final MarketOptions options;
    private final boolean headline;
    private List<VarBook.Node> nodes;
    private List<double[]> vectors;
    private int dataFirst;

    MarketWorkbook(MarketBooks.Built built, MarketOptions options, boolean headline) {
        this.built = built;
        this.options = options;
        this.headline = headline;
    }

    @Override
    public int data(Sheet data, int first) {
        VarBook b = built.book();
        nodes = new ArrayList<>(b.nodes().values());
        vectors = new ArrayList<>();
        for (VarBook.Node x : nodes) {
            vectors.add(b.aggregate(x.id()));
        }
        dataFirst = first;
        int n = b.width();
        Sheet header = data.row(first).text(0, "SCENARIO", "header").text(1, "DATE", "header");
        for (int c = 0; c < nodes.size(); c++) {
            header.text(c + 2, nodes.get(c).id(), "header");
        }
        for (int j = 0; j < n; j++) {
            Sheet r = data.row(first + 1 + j).number(0, j, "int").text(1, built.scenarios().get(j).toString(), "normal");
            for (int c = 0; c < nodes.size(); c++) {
                r.number(c + 2, vectors.get(c)[j], "decimal");
            }
        }
        return first + n;
    }

    @Override
    public int main(Workbook wb, Sheet main, int first) {
        VarCalc calc = options.calc();
        int n = built.book().width();
        int k = VarMath.rank(n, calc.confidence());
        int kes = VarMath.rank(n, calc.esConfidence());
        int conf = first;
        int esConf = first + 1;
        int scen = first + 2;
        int kRow = first + 3;
        int kesRow = first + 4;
        main.row(conf).text(0, "VaR confidence", "bold").number(1, calc.confidence(), "decimal");
        main.row(esConf).text(0, "ES confidence", "bold").number(1, calc.esConfidence(), "decimal");
        main.row(scen).text(0, "Scenarios", "bold").number(1, n, "int");
        main.row(kRow).text(0, "k (VaR)", "bold").formula(1, "MAX(1,MIN(B" + scen + ",CEILING(B" + scen + "*(1-B" + conf + ")-1E-9,1)))", (double) k, "int");
        main.row(kesRow).text(0, "k (ES)", "bold").formula(1, "MAX(1,MIN(B" + scen + ",CEILING(B" + scen + "*(1-B" + esConf + ")-1E-9,1)))", (double) kes, "int");
        int row = first + 6;
        main.row(row).text(0, "NODE_ID", "header").text(1, "VAR", "header").text(2, "ES", "header");
        int firstNode = row + 1;
        int rootRow = -1;
        String root = null;
        for (String h : built.book().hierarchies()) {
            VarBook.Node r = built.book().root(h);
            if (r != null) {
                root = r.id();
                break;
            }
        }
        for (int c = 0; c < nodes.size(); c++) {
            row++;
            String range = AnswerBook.range(c + 2, dataFirst + 1, dataFirst + n);
            VarCalc.Measures ms = calc.measures(vectors.get(c));
            String var;
            if (calc.rule() == VarMath.RankRule.INTERPOLATED) {
                double pos = n * (1.0 - calc.confidence());
                int lo = Math.max(1, (int) Math.floor(pos));
                int hi = Math.min(n, lo + 1);
                double w = Math.min(1.0, Math.max(0.0, pos - lo));
                var = "-((1-" + w + ")*SMALL(" + range + "," + lo + ")+" + w + "*SMALL(" + range + "," + hi + "))";
            } else {
                var = "-SMALL(" + range + ",$B$" + kRow + ")";
            }
            String es = "-SUMPRODUCT(SMALL(" + range + ",ROW($1:$" + kes + ")))/$B$" + kesRow;
            if (nodes.get(c).id().equals(root)) {
                rootRow = row;
            }
            main.row(row).text(0, nodes.get(c).id(), "normal").formula(1, var, ms.var(), "decimal").formula(2, es, ms.es(), "decimal");
        }
        if (!nodes.isEmpty()) {
            wb.name(AnswerBook.name("node-var", "VAR"), main.name(), "$B$" + firstNode);
            wb.name(AnswerBook.name("node-var", "ES"), main.name(), "$C$" + firstNode);
            if (headline && rootRow > 0) {
                wb.name(AnswerBook.name("headline", "VAR"), main.name(), "$B$" + rootRow);
                wb.name(AnswerBook.name("headline", "ES"), main.name(), "$C$" + rootRow);
            }
        }
        return row;
    }

    /** A zero-based column index as spreadsheet letters (0: A, 25: Z, 26: AA). */
    static String letters(int index) {
        return AnswerBook.letters(index);
    }
}
