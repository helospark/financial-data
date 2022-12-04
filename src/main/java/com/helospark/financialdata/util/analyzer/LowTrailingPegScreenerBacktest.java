package com.helospark.financialdata.util.analyzer;

import static com.helospark.financialdata.service.AltmanZCalculator.calculateAltmanZScore;
import static com.helospark.financialdata.service.DataLoader.readFinancials;
import static com.helospark.financialdata.service.GrowthAnalyzer.isProfitableEveryYearSince;
import static com.helospark.financialdata.service.GrowthAnalyzer.isStableGrowth;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import com.helospark.financialdata.domain.CompanyFinancials;
import com.helospark.financialdata.service.StandardAndPoorPerformanceProvider;
import com.helospark.financialdata.service.TrailingPegCalculator;

public class LowTrailingPegScreenerBacktest implements StockScreeners {

    @Override
    public void analyze(Set<String> symbols) {
        for (int yearsAgo = 0; yearsAgo <= 28; ++yearsAgo) {
            double growthSum = 0.0;
            int count = 0;
            System.out.println("symbol\t(Growth1, Growth2, Growth3)\t\tDCF\tPE\tUpside%\tfcfUpside%");
            for (var symbol : symbols) {
                CompanyFinancials company = readFinancials(symbol);
                var financials = company.financials;

                if (financials.isEmpty()) {
                    continue;
                }

                int latestElement = yearsAgo * 4;

                if (financials.size() > latestElement + 1) {

                    Optional<Double> trailingPeg = TrailingPegCalculator.calculateTrailingPeg(company, yearsAgo * 4);
                    Optional<Double> trailingPeg2 = TrailingPegCalculator.calculateTrailingPeg(company, yearsAgo * 4 + 1);
                    Optional<Double> trailingPeg3 = TrailingPegCalculator.calculateTrailingPeg(company, yearsAgo * 4 + 2);

                    double latestPriceThen = financials.get(latestElement).price;

                    boolean continouslyProfitable = isProfitableEveryYearSince(financials, 4 + yearsAgo, yearsAgo);
                    boolean stableGrowth = isStableGrowth(financials, 4 + yearsAgo, yearsAgo);
                    double altmanZ = financials.size() > latestElement ? calculateAltmanZScore(financials.get(latestElement), latestPriceThen) : 0.0;
                    //                    System.out.println(latestPriceThen + " " + continouslyProfitable + " " + stableGrowth + " " + altmanZ);

                    if (trailingPeg.isPresent() && trailingPeg2.isPresent() && trailingPeg3.isPresent() &&
                            stableGrowth &&
                            continouslyProfitable &&
                            altmanZ > 2.2 &&
                            financials.get(latestElement).incomeStatementTtm.eps > 0.0) {

                        double currentPe = latestPriceThen / financials.get(latestElement).incomeStatementTtm.eps;

                        if (trailingPeg.get() < 1.1 && trailingPeg2.get() < 1.1 && trailingPeg3.get() < 1.1 && currentPe > 20) {

                            int i = -1;
                            /*
                            for (i = latestElement - 1; i >= 0; --i) {
                                if (TrailingPegCalculator.calculateTrailingPeg(company, i).orElse(-1.0) > 2.0) {
                                    System.out.print(" b(" + company.financials.get(i).price + ") ");
                                    // break;
                                }
                                System.out.print(TrailingPegCalculator.calculateTrailingPeg(company, i).orElse(-1.0) + " ");
                            }
                            System.out.println();*/

                            double sellPrice = i > -1 ? company.financials.get(i).price : company.latestPrice;
                            double growthRatio = sellPrice / latestPriceThen;
                            growthSum += (growthRatio * 1000.0);
                            ++count;

                            double growthTillSell = (growthRatio - 1.0) * 100.0;

                            System.out.printf("%s\t(%.1f, %.1f, %.1f, %.1f)\t%.1f%% (%.1f -> %.1f)\t\t%s | %s\n", symbol,
                                    trailingPeg.get(), trailingPeg2.get(), trailingPeg3.get(), currentPe,
                                    growthTillSell, latestPriceThen, sellPrice, company.profile.companyName, company.profile.industry);
                        }
                    }
                }
            }
            double benchmark = StandardAndPoorPerformanceProvider.getGrowth(yearsAgo);
            double increase = (growthSum / (count * 1000) - 1.0);
            double annual = Math.pow(growthSum / (count * 1000), (1.0 / yearsAgo)) - 1.0;
            System.out.println("Have " + (growthSum) + " from " + (count * 1000) + " (" + (increase * 100.0) + "%, " + (annual * 100.0) + "%) invested sp500=" + benchmark + "\t"
                    + LocalDate.now().minusYears(yearsAgo).getYear());
            System.out.println();
        }
    }

}
