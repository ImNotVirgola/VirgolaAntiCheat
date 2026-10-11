package it.virgola.anticheat.checks;

import it.virgola.anticheat.config.Configs;

public abstract class AbstractCheck implements Check {

    protected final Configs configs;
    private boolean enabled;
    private boolean debug;

    protected AbstractCheck(Configs configs) {
        this.configs = configs;
        reloadFromConfig();
    }

    public void reloadFromConfig() {
        String base = "checks." + id().name();
        this.enabled = configs.checks().getBoolean(base + ".enabled", true);
        this.debug = configs.checks().getBoolean(base + ".debug", false);
    }

    @Override
    public boolean enabled() { return enabled; }

    @Override
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean debug() { return debug; }
}
