/* Generated by Together */

package com.pb.tlumip.ld;

import com.pb.models.pecas.AbstractTAZ;

public class DynamicPricesDevelopmentType extends DevelopmentType {
    private double portionVacantMultiplier;
    private double equilibriumVacancyRate;
    private double minimumBasePrice;

    public DynamicPricesDevelopmentType(String name, double portionVacantMultiplier, double equilibriumVacancyRate, double minimumBasePrice, int gridCode) {
        super(name, gridCode);
        this.portionVacantMultiplier = portionVacantMultiplier;
        this.minimumBasePrice = minimumBasePrice;
        this.equilibriumVacancyRate = equilibriumVacancyRate;
    }

    public void updatePrices(double elapsedTime) {
        AbstractTAZ[] zones = AbstractTAZ.getAllZones();
        for (int z = 0; z < zones.length; z++) {
            AbstractTAZ.PriceVacancy pv = zones[z].getPriceVacancySize(this);
            if (pv.getTotalSize() > 0) {
                double price = pv.getPrice();
                if (price < minimumBasePrice) price = minimumBasePrice;
                double extraVacancy = pv.getVacancy() / pv.getTotalSize() - equilibriumVacancyRate;
                double increment = price * extraVacancy * portionVacantMultiplier;
                double newPrice = pv.getPrice() - increment;
                if (newPrice < 0) newPrice = 0;
                zones[z].updatePrice(this, newPrice);
            }
        }
    }

    public String toString() { return "DevelopmentType " + name; };
}

