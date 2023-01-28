package com.helospark.financialdata.service;

import static com.helospark.financialdata.service.Helpers.findIndexWithOrBeforeDate;

import java.io.File;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Service;

import com.helospark.financialdata.CommonConfig;
import com.helospark.financialdata.domain.DataValuePairData;

@Service
public class CpiAdjustor {
    static List<DataValuePairData> cpiData = new ArrayList<>();

    static {

        // 1947 .. now
        List<DataValuePairData> newCpi = DataLoader.readListOfClassFromFile(new File(CommonConfig.BASE_FOLDER + "/info/CPI.json"), DataValuePairData.class);
        cpiData.addAll(newCpi);

        // 1913 .. 1947
        List<DataValuePairData> oldCpi = readClasspathCsv("/data/old_cpi.csv");
        Collections.reverse(oldCpi);
        cpiData.addAll(oldCpi);
    }

    public static double adjustForInflationToOldDate(double value, LocalDate oldDate, LocalDate newDate) {
        int oldIndex = findIndexWithOrBeforeDate(cpiData, oldDate);
        int newIndex = findIndexWithOrBeforeDate(cpiData, newDate);

        if (oldIndex == -1 || newIndex == -1) {
            return value;
        }

        double oldCpi = cpiData.get(oldIndex).value;
        double newCpi = cpiData.get(newIndex).value;

        double adjustment = newCpi / oldCpi;

        return value * adjustment;
    }

    private static List<DataValuePairData> readClasspathCsv(String string) {
        List<DataValuePairData> result = new ArrayList<>();
        try {
            InputStream file = CpiAdjustor.class.getResourceAsStream(string);
            String data = new String(file.readAllBytes());
            String[] lines = data.split("\n");
            for (int i = 1; i < lines.length; ++i) {
                String line = lines[i];
                String[] elements = line.split(",");
                int year = Integer.parseInt(elements[0]);
                for (int month = 1; month <= 12; ++month) {
                    double monthCpi = Double.parseDouble(elements[month]);

                    DataValuePairData resultElement = new DataValuePairData();
                    resultElement.date = LocalDate.of(year, month, 1);
                    resultElement.value = monthCpi;
                    result.add(resultElement);
                }
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
