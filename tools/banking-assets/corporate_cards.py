"""Generate the corporate item variants from the same original issuer artwork."""
from art import ASSETS, BANKS, output
from generate import card


def generate():
    for bank in BANKS:
        products = [('debit', None)] + [('credit', n.lower()) for n in bank['networks']]
        for product, network in products:
            suffix = bank['key'] + '_' + product + ('_' + network if network else '')
            texture = 'item/banking/corporate_' + suffix
            canvas = card(bank, product, network, corporate=True)
            canvas.save(ASSETS / 'textures' / (texture + '.png'))
            output(ASSETS / 'models/item' / ('issued_corporate_' + suffix + '.json'),
                   {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'economy:' + texture}})


if __name__ == '__main__':
    generate()
