package pl.kuba6000.ae2webintegration.core.api;

@SuppressWarnings("unused") // Gson reads the fields reflectively.
public class JSON_DetailedItem {

    public String itemid;
    public String itemname;
    public long quantity;
    public boolean craftable;
    public String itemKey;
    public String identityStatus;
}
