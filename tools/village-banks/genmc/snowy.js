const banks = [
    {
      key: "crownstone",
      name: "Crownstone Bank",
      currency: "CAD",
      primary: "#263B50",
      accent: "#D4A84C",
    },
    {
      key: "orchard_direct",
      name: "Orchard Direct",
      currency: "CAD",
      primary: "#E79768",
      accent: "#202C3E",
    },
    {
      key: "tidecrest",
      name: "Tidecrest Bank",
      currency: "CAD",
      primary: "#7FCBB8",
      accent: "#51324F",
    },
    {
      key: "crosswind",
      name: "Crosswind Bank",
      currency: "USD",
      primary: "#A34F39",
      accent: "#F4E6CE",
    },
    {
      key: "lantern_union",
      name: "Lantern Union",
      currency: "USD",
      primary: "#3C386B",
      accent: "#BC815B",
    },
    {
      key: "mesa_trail",
      name: "Mesa Trail Bank",
      currency: "USD",
      primary: "#D9BB84",
      accent: "#266D71",
    },
    {
      key: "asterbridge",
      name: "Asterbridge Bank",
      currency: "GBP",
      primary: "#68557F",
      accent: "#CCD3DD",
    },
    {
      key: "greenhaven",
      name: "Greenhaven Bank",
      currency: "GBP",
      primary: "#526B45",
      accent: "#DE977F",
    },
    {
      key: "meridian_grove",
      name: "Meridian Grove",
      currency: "EUR",
      primary: "#244D91",
      accent: "#D6A546",
    },
    {
      key: "slate_harbor",
      name: "Slate Harbor Bank",
      currency: "EUR",
      primary: "#303B45",
      accent: "#BDCB6E",
    },
    {
      key: "fjordlight",
      name: "Fjordlight Bank",
      currency: "DKK",
      primary: "#A1C9D7",
      accent: "#A65142",
    },
    {
      key: "harbor_meridian",
      name: "Harbor Meridian",
      currency: "HKD",
      primary: "#245864",
      accent: "#D9BC68",
    },
    {
      key: "jade_terrace",
      name: "Jade Terrace Bank",
      currency: "CNY",
      primary: "#50866F",
      accent: "#F1ECD6",
    },
    {
      key: "southern_arc",
      name: "Southern Arc Bank",
      currency: "AUD",
      primary: "#C69B48",
      accent: "#243B59",
    },
    {
      key: "fernreach",
      name: "Fernreach Bank",
      currency: "NZD",
      primary: "#603D60",
      accent: "#A0B9A0",
    },
    {
      key: "cedar_parcel",
      name: "Cedar Parcel Bank",
      currency: "JPY",
      primary: "#845B42",
      accent: "#EEE3C7",
    },
    {
      key: "lakeward_cantonal",
      name: "Lakeward Cantonal Bank",
      currency: "CHF",
      primary: "#306F94",
      accent: "#D6D3BC",
    },
    {
      key: "newhaven",
      name: "Newhaven Bank",
      currency: "CHF",
      primary: "#70384B",
      accent: "#E8DED4",
    },
    {
      key: "atlas_reserve",
      name: "Atlas Reserve",
      currency: "USD",
      primary: "#3C4149",
      accent: "#BB876B",
    },
    {
      key: "obsidian_capital",
      name: "Obsidian Capital Bank",
      currency: "EUR",
      primary: "#302F39",
      accent: "#DDD9CF",
    },
    {
      key: "alpine_vault",
      name: "Alpine Vault",
      currency: "CHF",
      primary: "#584358",
      accent: "#C7CDD4",
    },
    {
      key: "kintsugi_trust",
      name: "Kintsugi Trust",
      currency: "JPY",
      primary: "#253748",
      accent: "#B58B58",
    },
    {
      key: "mariner_crest",
      name: "Mariner Crest",
      currency: "AUD",
      primary: "#195866",
      accent: "#ECE5CE",
    },
  ],
  wood = "spruce",
  floor = "stone_bricks",
  foliage = "potted_spruce_sapling";
const concrete = [
  "white",
  "light_gray",
  "gray",
  "black",
  "brown",
  "red",
  "orange",
  "yellow",
  "lime",
  "green",
  "cyan",
  "light_blue",
  "blue",
  "purple",
  "magenta",
  "pink",
].map((c) => c + "_concrete");
function branch(b, large, ox, oz) {
  const w = large ? 17 : 11,
    d = large ? 11 : 9,
    e = large ? 8 : 5;
  const primary = voxel.nearest(b.primary, concrete),
    accent = voxel.nearest(b.accent, concrete);
  const B = (x, y, z, X, Y, Z, id) => voxel.box(ox + x, y, oz + z, ox + X, Y, oz + Z, id);
  const S = (x, y, z, id) => voxel.block(ox + x, y, oz + z, id);
  voxel.part(
    b.name + "/" + (large ? "Larger" : "Compact"),
    () => {
      voxel.part("Foundation", () => {
        B(0, 1, 0, w - 1, 1, d - 1, floor);
      });
      voxel.part("Modernist shell and glazing", () => {
        B(0, 2, 0, w - 1, 5, d - 1, "white_terracotta");
        B(1, 2, 1, w - 2, 5, d - 2, "air");
        for (const x of [0, e - 1, e + 1, w - 1]) B(x, 2, 0, x, 5, 0, "stripped_" + wood + "_log");
        B(1, 2, 0, e - 2, 2, 0, wood + "_planks");
        B(e + 2, 2, 0, w - 2, 2, 0, wood + "_planks");
        B(1, 3, 0, e - 2, 4, 0, "glass_pane[east=true,west=true]");
        B(e + 2, 3, 0, w - 2, 4, 0, "glass_pane[east=true,west=true]");
        B(0, 3, 2, 0, 4, d - 3, "glass_pane[north=true,south=true]");
        B(w - 1, 3, 2, w - 1, 4, d - 3, "glass_pane[north=true,south=true]");
        B(0, 2, d - 1, w - 1, 2, d - 1, wood + "_planks");
        B(e, 2, 0, e, 4, 0, "air");
        voxel.door(ox + e, 2, oz, wood + "_door", "south", { open: true });
      });
      voxel.part("Flat roof canopy and institution signage", () => {
        B(0, 5, 0, w - 1, 5, 0, primary);
        B(0, 6, 0, w - 1, 6, d - 1, "smooth_sandstone_slab[type=bottom]");
        B(e - 2, 5, 0, e + 2, 5, 0, accent);
        B(e - 2, 6, 0, e + 2, 6, 1, "smooth_sandstone_slab[type=top]");
        B(0, 5, 1, 0, 5, d - 1, primary);
        B(w - 1, 5, 1, w - 1, 5, d - 1, primary);
        B(e - 1, 6, Math.floor(d / 2), e + 1, 6, Math.floor(d / 2), "glass");
        S(e, 5, 1, primary);
        S(e, 5, 0, wood + "_wall_sign[facing=north]");
        B(w - 2, 6, d - 2, w - 1, 7, d - 1, primary);
        S(w - 2, 7, d - 2, accent);
      });
      voxel.part("Interior furnishings and clear service bays", () => {
        B(1, 1, 1, w - 2, 1, d - 2, wood + "_planks");
        const desks = large
          ? [
              [2, 7],
              [12, 7],
            ]
          : [[7, 5]];
        for (const [x, z] of desks) {
          B(x, 2, z, x + 1, 2, z, wood + "_slab[type=top]");
          S(x, 3, z, "black_stained_glass");
          S(x + 1, 3, z, "lectern[facing=north]");
        }
        const beds = large
          ? [
              [5, 8],
              [10, 8],
            ]
          : [[2, 6]];
        for (const [x, z] of beds) voxel.bed(ox + x, 2, oz + z, "white_bed", "south");
        B(1, 2, 4, 2, 2, 4, wood + "_slab[type=bottom]");
        S(w - 2, 2, d - 3, "bookshelf");
        S(w - 2, 3, d - 3, "potted_fern");
        for (const x of large ? [4, 8, 12] : [4, 7])
          S(x, 5, Math.floor(d / 2), "lantern[hanging=true]");
        S(1, 2, d - 2, foliage);
      });
    },
    { solid: true },
  );
}
for (let i = 0; i < banks.length; i++) {
  const x = 2 + (i % 5) * 36,
    z = 2 + Math.floor(i / 5) * 16;
  branch(banks[i], false, x, z);
  branch(banks[i], true, x + 14, z);
}
