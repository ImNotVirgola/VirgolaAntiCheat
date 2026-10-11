package it.virgola.anticheat.checks;

public enum CheckId {
    SCAFFOLD("Movement", "Detects scaffold/bridging hacks by analyzing block placement angles"),
    FLY("Movement", "Detects fly hacks by monitoring vertical movement patterns"),
    SPEED("Movement", "Detects speed hacks by comparing movement to maximum allowed speeds"),
    JESUS("Movement", "Detects water-walking hacks by checking liquid interactions"),
    NOFALL("Movement", "Detects NoFall hacks by monitoring fall damage"),
    BADPACKETS("Packet", "Detects invalid pitch values that exceed normal limits"),
    KILLAURA("Combat", "Detects combat hacks: Reach (hit distance), Angle (aim direction), NoSwing (missing animation), GCD (rotation patterns), MultiAura (multiple targets), Pattern (robotic consistency)");
    
    private final String type;
    private final String description;
    
    CheckId(String type, String description) {
        this.type = type;
        this.description = description;
    }
	
	public String path() {
	    return "checks." + name();
	}
	
	public String type() {
	    return type;
	}
	
	public String description() {
	    return description;
	}

    public static CheckId fromString(String s) {
        if (s == null) return null;
        try {
            return CheckId.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
