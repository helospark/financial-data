package com.helospark.financialdata;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.helospark.financialdata.domain.CompanyFinancials;
import com.helospark.financialdata.management.user.LoginController;
import com.helospark.financialdata.management.user.ViewedStocksService;
import com.helospark.financialdata.management.user.repository.AccountType;
import com.helospark.financialdata.management.user.repository.FreeStockRepository;
import com.helospark.financialdata.service.DataLoader;
import com.helospark.financialdata.service.GrowthCalculator;
import com.helospark.financialdata.service.MarginCalculator;
import com.helospark.financialdata.service.SymbolIndexProvider;

import jakarta.servlet.http.HttpServletRequest;

@Controller
@RequestMapping("/")
public class ViewController {
    @Autowired
    private SymbolIndexProvider symbolIndexProvider;
    @Autowired
    private LoginController loginController;
    @Autowired
    private ViewedStocksService viewedStocksService;
    @Autowired
    private FreeStockRepository freeStockRepository;

    @GetMapping("/stock/{stock}")
    public String stock(@PathVariable("stock") String stock, Model model, HttpServletRequest request, RedirectAttributes redirectAttributes) {
        if (!symbolIndexProvider.getCompanyName(stock).isPresent()) {
            redirectAttributes.addAttribute("error", "not_found");
            return "redirect:/";
        } else {
            fillModelWithCommonStockData(stock, model, request);
            return "stock";
        }
    }

    @GetMapping("/stock")
    public String stockSearch(Model model, @RequestParam("error") String error) {
        model.addAttribute("error", error);
        return "stock_search";
    }

    @GetMapping("/")
    public String index(Model model) {
        return "index";
    }

    @GetMapping("/calculator/{stock}")
    public String calculator(@PathVariable("stock") String stock, Model model, HttpServletRequest request, RedirectAttributes redirectAttributes) {
        if (!symbolIndexProvider.getCompanyName(stock).isPresent()) {
            redirectAttributes.addAttribute("error", "not_found");
            return "redirect:/";
        } else {
            fillModelWithCommonStockData(stock, model, request);

            if (Boolean.TRUE.equals(model.getAttribute("allowed"))) {
                CompanyFinancials company = DataLoader.readFinancials(stock);
                if (company.financials.size() > 0) {
                    double revenueGrowth = GrowthCalculator.getMedianRevenueGrowth(company.financials, 8, 0.0).orElse(10.0);
                    double margin = MarginCalculator.getAvgNetMargin(company.financials, 0);
                    double shareCountGrowth = GrowthCalculator.getShareCountGrowthInInterval(company.financials, 5, 0).orElse(0.0);
                    double endGrowth = revenueGrowth * 0.5;
                    double endMultiple = 12;

                    if (endGrowth > 12) {
                        endMultiple = endGrowth;
                    }
                    if (endMultiple > 24) {
                        endMultiple = 24;
                    }

                    model.addAttribute("revenue", (double) company.financials.get(0).incomeStatementTtm.revenue / 1_000_000);
                    model.addAttribute("startGrowth", String.format("%.2f", revenueGrowth));
                    model.addAttribute("endGrowth", String.format("%.2f", endGrowth));
                    model.addAttribute("startMargin", String.format("%.2f", margin * 100.0));
                    model.addAttribute("endMargin", String.format("%.2f", margin * 100.0));
                    model.addAttribute("shareChange", String.format("%.2f", shareCountGrowth));
                    model.addAttribute("shareCount", company.financials.get(0).incomeStatementTtm.weightedAverageShsOut / 1000);
                    model.addAttribute("endMultiple", String.format("%.0f", endMultiple));
                }

                model.addAttribute("latestPrice", company.latestPrice);
            }

            return "calculator";
        }
    }

    public void fillModelWithCommonStockData(String stock, Model model, HttpServletRequest request) {
        Optional<DecodedJWT> jwtOptional = loginController.getJwt(request);

        if (jwtOptional.isPresent()) {
            DecodedJWT jwt = jwtOptional.get();
            AccountType accountType = loginController.getAccountType(jwt);
            boolean allowed = viewedStocksService.getAndUpdateAllowViewStocks(jwt.getSubject(), accountType, stock);

            model.addAttribute("accountType", accountType.toString());
            model.addAttribute("viewLimit", viewedStocksService.getAllowedViewCount(accountType));
            if (!allowed) {
                model.addAttribute("allowed", false);
            } else {
                model.addAttribute("allowed", true);
            }
        } else {
            List<String> freeSockList = freeStockRepository.getFreeSockList();
            model.addAttribute("accountType", "NOT_LOGGED_IN");
            if (!freeSockList.contains(stock)) {
                model.addAttribute("allowed", false);
            } else {
                model.addAttribute("allowed", true);
            }
        }

        Optional<String> companyNameOptional = symbolIndexProvider.getCompanyName(stock);
        String companyName = stock;
        if (companyNameOptional.isPresent()) {
            companyName = companyNameOptional.get();
        }
        model.addAttribute("stock", stock);
        if (Boolean.TRUE.equals(model.getAttribute("allowed"))) {
            model.addAttribute("stockToLoad", stock);
        } else {
            model.addAttribute("stockToLoad", "INTC");
        }
        model.addAttribute("companyName", companyName);

    }

}