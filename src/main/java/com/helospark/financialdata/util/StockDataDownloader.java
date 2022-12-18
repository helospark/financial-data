package com.helospark.financialdata.util;

import static com.helospark.financialdata.CommonConfig.BASE_FOLDER;
import static java.time.format.DateTimeFormatter.ISO_DATE;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.RateLimiter;
import com.helospark.financialdata.domain.CompanyListElement;
import com.helospark.financialdata.domain.FxSupportedSymbolsResponse;
import com.helospark.financialdata.domain.Profile;
import com.helospark.financialdata.service.DataLoader;
import com.helospark.financialdata.service.exchanges.ExchangeRegion;
import com.helospark.financialdata.service.exchanges.Exchanges;
import com.helospark.financialdata.service.exchanges.MarketType;

public class StockDataDownloader {
    public static final String SYMBOL_CACHE_FILE = BASE_FOLDER + "/info/symbols/symbols.csv";
    static final ObjectMapper objectMapper = new ObjectMapper();
    static final String API_KEY = System.getProperty("API_KEY");
    static final Integer NUM_YEARS = 100;
    static final Integer NUM_QUARTER = NUM_YEARS * 4;
    static final String FX_BASE_FOLDER = BASE_FOLDER + "/fxratefiles";
    static final String BASE_URL = "https://financialmodelingprep.com/api";
    static final int RATE_LIMIT_PER_MINUTE = 250;

    static RateLimiter rateLimiter = RateLimiter.create(RATE_LIMIT_PER_MINUTE / 60.0);

    public static void main(String[] args) {
        init();

        List<String> symbols = Arrays.asList(downloadSimpleUrlCached("/v3/financial-statement-symbol-lists", "info/financial-statement-symbol-lists.json", String[].class));

        List<String> sp500Symbols = downloadCompanyListCached("/v3/sp500_constituent", "info/sp500_constituent.json");
        List<String> nasdaqSymbols = downloadCompanyListCached("/v3/nasdaq_constituent", "info/nasdaq_constituent.json");
        List<String> dowjones_constituent = downloadCompanyListCached("/v3/dowjones_constituent", "info/dowjones_constituent.json");
        downloadFxRates();
        downloadUsefulInfo();

        for (var symbol : sp500Symbols) {
            downloadStockData(symbol);
        }
        for (var symbol : nasdaqSymbols) {
            downloadStockData(symbol);
        }
        for (var symbol : dowjones_constituent) {
            downloadStockData(symbol);
        }
        for (var symbol : symbols) {
            downloadStockData(symbol);
        }

        createExchangeCache();
        createSymbolCache();
    }

    private static void createExchangeCache() {
        Map<String, List<String>> exchangeToSymbol = new HashMap<>();
        Map<String, String> exchangeToName = new HashMap<>();
        Set<String> allSymbols = DataLoader.provideAllSymbols();

        for (var symbol : allSymbols) {
            List<Profile> profiles = DataLoader.readFinancialFile(symbol, "profile.json", Profile.class);
            if (profiles.size() > 0 && profiles.get(0).exchangeShortName != null) {
                putToMultiMap(exchangeToSymbol, profiles.get(0).exchangeShortName, symbol);
                exchangeToName.put(profiles.get(0).exchangeShortName, profiles.get(0).exchange);
            } else {
                putToMultiMap(exchangeToSymbol, "UNKNOWN", symbol);
            }
        }

        for (var entry : exchangeToSymbol.entrySet()) {
            File file = new File(BASE_FOLDER + "/info/exchanges/" + entry.getKey());
            if (!file.exists()) {
                file.getParentFile().mkdirs();

                String valueToWrite = entry.getValue().stream().collect(Collectors.joining("\n"));

                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(valueToWrite.getBytes());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }

    }

    private static void createSymbolCache() {
        Set<String> symbolCompanyNameCache = new LinkedHashSet<>();

        Set<Profile> usCompanies = new TreeSet<>((a, b) -> Double.compare(b.mktCap, a.mktCap));
        // sort by most often searched regions
        for (var symbol : DataLoader.provideSymbolsIn(Exchanges.getExchangesByRegion(ExchangeRegion.US))) {
            List<Profile> profiles = DataLoader.readFinancialFile(symbol, "profile.json", Profile.class);
            if (profiles.size() > 0 && !symbol.endsWith("-PL")) {
                usCompanies.add(profiles.get(0));
            }
        }
        for (var usCompany : usCompanies) {
            symbolCompanyNameCache.add(symbolToSearchData(usCompany.symbol));
        }
        for (var symbol : DataLoader.provideSymbolsIn(Exchanges.getExchangesByRegion(ExchangeRegion.US))) {
            String information = symbolToSearchData(symbol);
            symbolCompanyNameCache.add(information);
        }
        for (var symbol : DataLoader.provideSymbolsIn(Exchanges.getExchangesByType(MarketType.DEVELOPED_MARKET))) {
            String information = symbolToSearchData(symbol);
            symbolCompanyNameCache.add(information);
        }
        for (var symbol : DataLoader.provideSymbolsIn(Exchanges.getExchangesByType(MarketType.DEVELOPING_MARKET))) {
            String information = symbolToSearchData(symbol);
            symbolCompanyNameCache.add(information);
        }
        for (var symbol : DataLoader.provideAllSymbols()) {
            String information = symbolToSearchData(symbol);
            symbolCompanyNameCache.add(information);
        }

        File file = new File(SYMBOL_CACHE_FILE);
        if (!file.exists()) {
            file.getParentFile().mkdirs();

            String valueToWrite = symbolCompanyNameCache.stream().collect(Collectors.joining("\n"));

            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(valueToWrite.getBytes());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

    }

    public static String symbolToSearchData(String symbol) {
        List<Profile> profiles = DataLoader.readFinancialFile(symbol, "profile.json", Profile.class);

        String information = symbol + ";";

        if (profiles.size() > 0 && profiles.get(0).companyName != null) {
            information += profiles.get(0).companyName;
        }
        return information;
    }

    private static void downloadUsefulInfo() {
        //https: //financialmodelingprep.com/api/v4/treasury?from=2021-06-30&to=2021-09-30&apikey=API_KEY
        downloadUrlIfNeeded("info/tresury_rates.json", "/v4/treasury", Map.of("from", "1990-01-01", "to", LocalDate.now().toString()));
        // https://financialmodelingprep.com/api/v3/historical-price-full/%5EGSPC?serietype=line&apikey=API_KEY
        downloadUrlIfNeeded("info/s&p500_price.json", "/v3/historical-price-full/%5EGSPC", Map.of("serietype", "line"));
        // https://financialmodelingprep.com/api/v3/historical/sp500_constituent?apikey=API_KEY
        downloadUrlIfNeeded("info/s&p500_historical_constituent.json", "/v3/historical/sp500_constituent", Map.of());
        // https://financialmodelingprep.com/api/v3/historical/dowjones_constituent?apikey=API_KEY
        downloadUrlIfNeeded("info/dowjones_constituent_historical_constituent.json", "/v3/historical/dowjones_constituent", Map.of());

        for (var element : List.of("GDP", "realGDP", "nominalPotentialGDP", "realGDPPerCapita", "federalFunds", "CPI",
                "inflationRate", "inflation", "retailSales", "consumerSentiment", "durableGoods",
                "unemploymentRate", "totalNonfarmPayroll", "initialClaims", "industrialProductionTotalIndex",
                "newPrivatelyOwnedHousingUnitsStartedTotalUnits", "totalVehicleSales", "retailMoneyFunds",
                "smoothedUSRecessionProbabilities", "3MonthOr90DayRatesAndYieldsCertificatesOfDeposit",
                "commercialBankInterestRateOnCreditCardPlansAllAccounts", "30YearFixedRateMortgageAverage",
                "15YearFixedRateMortgageAverage")) {
            downloadUrlIfNeeded("info/" + element + ".json", "/v4/economic", Map.of("name", element, "from", "1920-01-01", "to", LocalDate.now().format(ISO_DATE)));
        }

    }

    private static void downloadFxRates() {
        try {
            File symbolsFile = new File(FX_BASE_FOLDER + "/symbols.json");
            if (!symbolsFile.exists()) {
                String symbolsUri2 = "https://api.exchangerate.host/symbols";
                System.out.println(symbolsUri2);
                symbolsFile.getParentFile().mkdirs();
                String data = IOUtils.toString(URI.create(symbolsUri2), StandardCharsets.UTF_8);

                try (FileOutputStream fos = new FileOutputStream(symbolsFile)) {
                    fos.write(data.getBytes());
                }
            }
            FxSupportedSymbolsResponse symbols = objectMapper.readValue(symbolsFile, FxSupportedSymbolsResponse.class);

            Set<String> currencies = symbols.symbols.keySet();

            for (var currency : currencies) {
                LocalDate localDate = LocalDate.of(2000, 1, 1);
                while (localDate.getYear() <= LocalDate.now().getYear()) {
                    File currencyFile = new File(FX_BASE_FOLDER + "/" + currency + "_" + localDate.getYear() + ".json");
                    if (!currencyFile.exists()) {
                        rateLimiter.acquire();
                        String uri = "https://api.exchangerate.host/timeseries?start_date=" + localDate.toString() + "&end_date=" + localDate.plusYears(1).toString() + "&base=" + currency;
                        System.out.println(uri);
                        String data = IOUtils.toString(URI.create(uri), StandardCharsets.UTF_8);

                        try (FileOutputStream fos = new FileOutputStream(currencyFile)) {
                            fos.write(data.getBytes());
                        }
                    }
                    localDate = localDate.plusYears(1);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void downloadStockData(String symbol) {
        symbol = symbol.replace("^", "%5E");
        Map<String, String> queryMap = new HashMap<>(Map.of("limit", asString(NUM_QUARTER)));

        queryMap.put("period", "quarter");

        //https://financialmodelingprep.com/api/v3/income-statement/AAPL?limit=120&apikey=API_KEY
        downloadUrlIfNeeded("fundamentals/" + symbol + "/income-statement.json", "/v3/income-statement/" + symbol, queryMap);
        //https://financialmodelingprep.com/api/v3/balance-sheet-statement/AAPL?period=quarter&limit=400&apikey=API_KEY
        downloadUrlIfNeeded("fundamentals/" + symbol + "/balance-sheet.json", "/v3/balance-sheet-statement/" + symbol, queryMap);
        // https://financialmodelingprep.com/api/v3/cash-flow-statement/AAPL?limit=120&apikey=API_KEY
        downloadUrlIfNeeded("fundamentals/" + symbol + "/cash-flow.json", "/v3/cash-flow-statement/" + symbol, queryMap);
        // https://financialmodelingprep.com/api/v3/ratios/AAPL?limit=40&apikey=API_KEY
        downloadUrlIfNeeded("fundamentals/" + symbol + "/ratios.json", "/v3/ratios/" + symbol, queryMap);
        // https://financialmodelingprep.com/api/v3/enterprise-values/AAPL?limit=40&apikey=API_KEY
        downloadUrlIfNeeded("fundamentals/" + symbol + "/enterprise-values.json", "/v3/enterprise-values/" + symbol, queryMap);
        // https://financialmodelingprep.com/api/v3/key-metrics/AAPL?limit=40&apikey=API_KEY
        downloadUrlIfNeeded("fundamentals/" + symbol + "/key-metrics.json", "/v3/key-metrics/" + symbol, queryMap);
        // https://financialmodelingprep.com/api/v3/historical-price-full/AAPL?serietype=line&apikey=API_KEY
        downloadUrlIfNeeded("fundamentals/" + symbol + "/historical-price.json", "/v3/historical-price-full/" + symbol, Map.of("serietype", "line"));
        // https://financialmodelingprep.com/api/v3/profile/AAPL?apikey=API_KEY
        downloadUrlIfNeeded("fundamentals/" + symbol + "/profile.json", "/v3/profile/" + symbol, Map.of());
    }

    private static void init() {
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private static <T> T downloadSimpleUrlCached(String urlPath, String folder, Class<T> clazz) {
        return downloadCachedUrlInternal(folder, urlPath, clazz, Map.of());
    }

    private static List<String> downloadCompanyListCached(String urlPath, String folder) {
        CompanyListElement[] elements = downloadCachedUrlInternal(folder, urlPath, CompanyListElement[].class, Map.of("limit", asString(NUM_QUARTER)));

        return Arrays.stream(elements)
                .map(e -> e.getSymbol())
                .collect(Collectors.toList());
    }

    private static String asString(Integer numQuarter) {
        return String.valueOf(numQuarter);
    }

    private static <T> T downloadCachedUrlInternal(String folder, String uriPath, Class<T> clazz, Map<String, String> queryParams) {
        File absoluteFile = downloadUrlIfNeeded(folder, uriPath, queryParams);
        try (FileInputStream fis = new FileInputStream(absoluteFile)) {
            return objectMapper.readValue(fis.readAllBytes(), clazz);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static File downloadUrlIfNeeded(String folderAndfile, String uriPath, Map<String, String> queryParams) {
        File result = null;
        int counter = 0;

        while (result == null && counter++ < 3) {
            try {
                result = downloadUrlIfNeededWithoutRetry(folderAndfile, uriPath, queryParams);
            } catch (Exception e) {
                e.printStackTrace();
                noExceptionSleep();
            }
        }
        if (result == null) {
            throw new RuntimeException("Couldn't download file: " + uriPath);
        }
        return result;
    }

    private static void noExceptionSleep() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static File downloadUrlIfNeededWithoutRetry(String folderAndfile, String uriPath, Map<String, String> queryParams) {
        File absoluteFile = new File(BASE_FOLDER + "/" + folderAndfile);
        if (!absoluteFile.exists()) {
            try {
                absoluteFile.getParentFile().mkdirs();

                String fullUri = BASE_URL + uriPath + "?apikey=" + API_KEY;
                for (var entry : queryParams.entrySet()) {
                    fullUri += ("&" + entry.getKey() + "=" + entry.getValue());
                }

                System.out.println(fullUri);
                rateLimiter.acquire();
                String data = IOUtils.toString(URI.create(fullUri), StandardCharsets.UTF_8);

                if (data.contains("Error Message")) {
                    throw new RuntimeException("Couldn't read data " + data);
                }

                try (FileOutputStream fos = new FileOutputStream(absoluteFile)) {
                    fos.write(data.getBytes());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return absoluteFile;
    }

    private static void putToMultiMap(Map<String, List<String>> exchangeToSymbol, String key, String value) {
        List<String> values = exchangeToSymbol.get(key);
        if (values == null) {
            values = new ArrayList<>();
            exchangeToSymbol.put(key, values);
        }
        values.add(value);
    }

}
