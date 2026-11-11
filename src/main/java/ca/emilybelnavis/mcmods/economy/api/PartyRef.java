package ca.emilybelnavis.mcmods.economy.api;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import java.util.Objects;

/** Stable account owner reference in one financial world. */
public record PartyRef(WorldId world, Owner owner) {
  public PartyRef {
    Objects.requireNonNull(world);
    Objects.requireNonNull(owner);
  }

  public sealed interface Owner permits Player, Organization, System {}

  public record Player(PlayerId id) implements Owner {
    public Player {
      Objects.requireNonNull(id);
    }
  }

  public record Organization(OrganizationId id) implements Owner {
    public Organization {
      Objects.requireNonNull(id);
    }
  }

  public record System(SystemActorId id) implements Owner {
    public System {
      Objects.requireNonNull(id);
    }
  }
}
