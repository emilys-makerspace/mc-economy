package ca.emilybelnavis.mcmods.economy.trade.cash;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.journal.WithdrawalPlanner;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Frozen v1 extraction of original CashCatalog register declarations; no Minecraft class loading. */
public final class CashDenominations {
    private CashDenominations() {}
    public static final String SHA256="1be0052d2dedefdb34021f24c3d07e0eae7e590a0f39230aeef7eff18e40323c";
    private static final String MANIFEST="""
        economy:aud_bill_10|AUD|1000|true
        economy:aud_bill_100|AUD|10000|true
        economy:aud_bill_20|AUD|2000|true
        economy:aud_bill_5|AUD|500|true
        economy:aud_bill_50|AUD|5000|true
        economy:aud_coin_10|AUD|10|false
        economy:aud_coin_100|AUD|100|false
        economy:aud_coin_20|AUD|20|false
        economy:aud_coin_200|AUD|200|false
        economy:aud_coin_5|AUD|5|false
        economy:aud_coin_50|AUD|50|false
        economy:cad_bill_10|CAD|1000|true
        economy:cad_bill_100|CAD|10000|true
        economy:cad_bill_100_horizontal|CAD|10000|true
        economy:cad_bill_10_horizontal|CAD|1000|true
        economy:cad_bill_20|CAD|2000|true
        economy:cad_bill_20_horizontal|CAD|2000|true
        economy:cad_bill_5|CAD|500|true
        economy:cad_bill_50|CAD|5000|true
        economy:cad_bill_50_horizontal|CAD|5000|true
        economy:cad_bill_5_horizontal|CAD|500|true
        economy:cad_coin_10|CAD|10|false
        economy:cad_coin_100|CAD|100|false
        economy:cad_coin_200|CAD|200|false
        economy:cad_coin_25|CAD|25|false
        economy:cad_coin_5|CAD|5|false
        economy:cad_coin_50|CAD|50|false
        economy:chf_bill_10|CHF|1000|true
        economy:chf_bill_100|CHF|10000|true
        economy:chf_bill_1000|CHF|100000|true
        economy:chf_bill_20|CHF|2000|true
        economy:chf_bill_200|CHF|20000|true
        economy:chf_bill_50|CHF|5000|true
        economy:chf_coin_10|CHF|10|false
        economy:chf_coin_100|CHF|100|false
        economy:chf_coin_20|CHF|20|false
        economy:chf_coin_200|CHF|200|false
        economy:chf_coin_5|CHF|5|false
        economy:chf_coin_50|CHF|50|false
        economy:chf_coin_500|CHF|500|false
        economy:cny_bill_1|CNY|100|true
        economy:cny_bill_10|CNY|1000|true
        economy:cny_bill_100|CNY|10000|true
        economy:cny_bill_20|CNY|2000|true
        economy:cny_bill_5|CNY|500|true
        economy:cny_bill_50|CNY|5000|true
        economy:cny_coin_10|CNY|10|false
        economy:cny_coin_100|CNY|100|false
        economy:cny_coin_50|CNY|50|false
        economy:dkk_bill_100|DKK|10000|true
        economy:dkk_bill_200|DKK|20000|true
        economy:dkk_bill_50|DKK|5000|true
        economy:dkk_bill_500|DKK|50000|true
        economy:dkk_coin_100|DKK|100|false
        economy:dkk_coin_1000|DKK|1000|false
        economy:dkk_coin_200|DKK|200|false
        economy:dkk_coin_2000|DKK|2000|false
        economy:dkk_coin_50|DKK|50|false
        economy:dkk_coin_500|DKK|500|false
        economy:eur_bill_10|EUR|1000|true
        economy:eur_bill_100|EUR|10000|true
        economy:eur_bill_20|EUR|2000|true
        economy:eur_bill_200|EUR|20000|true
        economy:eur_bill_5|EUR|500|true
        economy:eur_bill_50|EUR|5000|true
        economy:eur_coin_1|EUR|1|false
        economy:eur_coin_10|EUR|10|false
        economy:eur_coin_100|EUR|100|false
        economy:eur_coin_2|EUR|2|false
        economy:eur_coin_20|EUR|20|false
        economy:eur_coin_200|EUR|200|false
        economy:eur_coin_5|EUR|5|false
        economy:eur_coin_50|EUR|50|false
        economy:gbp_bill_10|GBP|1000|true
        economy:gbp_bill_20|GBP|2000|true
        economy:gbp_bill_5|GBP|500|true
        economy:gbp_bill_50|GBP|5000|true
        economy:gbp_coin_1|GBP|1|false
        economy:gbp_coin_10|GBP|10|false
        economy:gbp_coin_100|GBP|100|false
        economy:gbp_coin_2|GBP|2|false
        economy:gbp_coin_20|GBP|20|false
        economy:gbp_coin_200|GBP|200|false
        economy:gbp_coin_5|GBP|5|false
        economy:gbp_coin_50|GBP|50|false
        economy:hkd_bill_10|HKD|1000|true
        economy:hkd_bill_100|HKD|10000|true
        economy:hkd_bill_1000|HKD|100000|true
        economy:hkd_bill_20|HKD|2000|true
        economy:hkd_bill_50|HKD|5000|true
        economy:hkd_bill_500|HKD|50000|true
        economy:hkd_coin_10|HKD|10|false
        economy:hkd_coin_100|HKD|100|false
        economy:hkd_coin_1000|HKD|1000|false
        economy:hkd_coin_20|HKD|20|false
        economy:hkd_coin_200|HKD|200|false
        economy:hkd_coin_50|HKD|50|false
        economy:hkd_coin_500|HKD|500|false
        economy:jpy_bill_1000|JPY|1000|true
        economy:jpy_bill_10000|JPY|10000|true
        economy:jpy_bill_2000|JPY|2000|true
        economy:jpy_bill_5000|JPY|5000|true
        economy:jpy_coin_1|JPY|1|false
        economy:jpy_coin_10|JPY|10|false
        economy:jpy_coin_100|JPY|100|false
        economy:jpy_coin_5|JPY|5|false
        economy:jpy_coin_50|JPY|50|false
        economy:jpy_coin_500|JPY|500|false
        economy:nzd_bill_10|NZD|1000|true
        economy:nzd_bill_100|NZD|10000|true
        economy:nzd_bill_20|NZD|2000|true
        economy:nzd_bill_5|NZD|500|true
        economy:nzd_bill_50|NZD|5000|true
        economy:nzd_coin_10|NZD|10|false
        economy:nzd_coin_100|NZD|100|false
        economy:nzd_coin_20|NZD|20|false
        economy:nzd_coin_200|NZD|200|false
        economy:nzd_coin_50|NZD|50|false
        economy:usd_bill_1|USD|100|true
        economy:usd_bill_10|USD|1000|true
        economy:usd_bill_100|USD|10000|true
        economy:usd_bill_2|USD|200|true
        economy:usd_bill_20|USD|2000|true
        economy:usd_bill_5|USD|500|true
        economy:usd_bill_50|USD|5000|true
        economy:usd_coin_1|USD|1|false
        economy:usd_coin_10|USD|10|false
        economy:usd_coin_100|USD|100|false
        economy:usd_coin_25|USD|25|false
        economy:usd_coin_5|USD|5|false
        economy:usd_coin_50|USD|50|false
        """.strip();
    private static final Map<String,WithdrawalPlanner.Denomination> VALUES=load();
    private static Map<String,WithdrawalPlanner.Denomination> load(){
        try{String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(MANIFEST.getBytes(StandardCharsets.UTF_8)));if(!hash.equals(SHA256))throw new IllegalStateException("Frozen cash manifest changed");}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        var values=new TreeMap<String,WithdrawalPlanner.Denomination>();for(String line:MANIFEST.split("\n")){String[] p=line.split("\\|",-1);var denomination=new WithdrawalPlanner.Denomination(p[0],EconomyCurrency.valueOf(p[1]),Long.parseLong(p[2]),Boolean.parseBoolean(p[3]));if(values.put(p[0],denomination)!=null)throw new IllegalStateException("Duplicate denomination");}return Collections.unmodifiableMap(values);
    }
    public static WithdrawalPlanner.Denomination require(String id){var value=VALUES.get(id);if(value==null)throw new IllegalArgumentException("Unknown original cash denomination");return value;}
    public static List<WithdrawalPlanner.Denomination> forCurrency(EconomyCurrency currency){return VALUES.values().stream().filter(d->d.currency()==currency).toList();}
}
