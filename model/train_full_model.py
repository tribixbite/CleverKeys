#!/usr/bin/env python3
"""
Train character-level swipe typing model on full dataset to achieve 70% accuracy.
This uses the complete combined dataset for maximum performance.
"""

import os
import sys
import json
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import Dataset, DataLoader
import numpy as np
from typing import Dict, List, Tuple, Optional
import math
from pathlib import Path
from tqdm import tqdm
import random

# Import model components from the character model
from train_character_model import (
    KeyboardGrid, 
    CharTokenizer, 
    SwipeDataset,
    CharacterLevelSwipeModel
)


def train_full_model():
    """Train on full dataset to achieve target 70% accuracy."""
    
    # Configuration for full training
    batch_size = 64  # Larger batch for better gradient estimates
    learning_rate = 5e-4  # Slightly higher LR for faster convergence
    num_epochs = 50  # More epochs to reach target
    patience = 15  # Early stopping patience
    device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    
    print("="*60)
    print("Training Full Character-Level Swipe Model")
    print("="*60)
    print(f"Device: {device}")
    print(f"Target: 70% word accuracy (matching original model)")
    print("-"*60)
    
    # Use full combined dataset
    train_data_path = 'data/combined_dataset/cleaned_english_swipes_train.jsonl'
    val_data_path = 'data/combined_dataset/cleaned_english_swipes_val.jsonl'
    test_data_path = 'data/combined_dataset/cleaned_english_swipes_test.jsonl'
    
    # Check dataset exists
    if not os.path.exists(train_data_path):
        print(f"Error: Dataset not found at {train_data_path}")
        return
    
    print(f"Loading datasets...")
    
    # Load full datasets - no max_samples limit
    train_dataset = SwipeDataset(train_data_path)  # Full 68k samples
    val_dataset = SwipeDataset(val_data_path)      # Full validation set
    test_dataset = SwipeDataset(test_data_path)    # Test set for final eval
    
    print(f"Train: {len(train_dataset)} samples")
    print(f"Val: {len(val_dataset)} samples")
    print(f"Test: {len(test_dataset)} samples")
    print("-"*60)
    
    # Create dataloaders with num_workers for faster loading
    train_loader = DataLoader(
        train_dataset, 
        batch_size=batch_size, 
        shuffle=True,
        num_workers=4,
        pin_memory=True
    )
    val_loader = DataLoader(
        val_dataset, 
        batch_size=batch_size, 
        shuffle=False,
        num_workers=4,
        pin_memory=True
    )
    test_loader = DataLoader(
        test_dataset,
        batch_size=batch_size,
        shuffle=False,
        num_workers=4,
        pin_memory=True
    )
    
    # Create model with optimal architecture
    tokenizer = CharTokenizer()
    model = CharacterLevelSwipeModel(
        traj_dim=6,
        d_model=256,  # Larger model for better capacity
        nhead=8,
        num_encoder_layers=6,  # Deeper encoder
        num_decoder_layers=4,  # Deeper decoder
        dim_feedforward=1024,  # Larger feedforward
        dropout=0.1,
        char_vocab_size=tokenizer.vocab_size,
        kb_vocab_size=tokenizer.vocab_size
    ).to(device)
    
    # Count parameters
    param_count = sum(p.numel() for p in model.parameters() if p.requires_grad)
    print(f"Model parameters: {param_count:,}")
    print(f"Model size (FP32): {param_count * 4 / 1024 / 1024:.2f} MB")
    print("-"*60)
    
    # Loss and optimizer
    criterion = nn.CrossEntropyLoss(ignore_index=tokenizer.pad_idx)
    optimizer = torch.optim.AdamW(model.parameters(), lr=learning_rate, weight_decay=0.01)
    
    # Learning rate scheduler - cosine annealing with warmup
    warmup_epochs = 2
    scheduler = torch.optim.lr_scheduler.OneCycleLR(
        optimizer,
        max_lr=learning_rate,
        epochs=num_epochs,
        steps_per_epoch=len(train_loader),
        pct_start=warmup_epochs/num_epochs,
        anneal_strategy='cos'
    )
    
    # Training tracking
    best_val_acc = 0
    patience_counter = 0
    checkpoint_dir = Path('checkpoints/full_character_model')
    checkpoint_dir.mkdir(parents=True, exist_ok=True)
    
    print("Starting training...")
    print("="*60)
    
    for epoch in range(num_epochs):
        # Training phase
        model.train()
        train_loss = 0
        train_correct = 0
        train_total = 0
        
        pbar = tqdm(train_loader, desc=f'Epoch {epoch+1}/{num_epochs} [Train]')
        for batch_idx, batch in enumerate(pbar):
            traj_features = batch['traj_features'].to(device)
            nearest_keys = batch['nearest_keys'].to(device)
            targets = batch['target'].to(device)
            
            # Create masks
            seq_lens = batch['seq_len']
            src_mask = torch.zeros(traj_features.shape[0], traj_features.shape[1], dtype=torch.bool, device=device)
            for i, seq_len in enumerate(seq_lens):
                src_mask[i, seq_len:] = True
            
            tgt_mask = (targets[:, :-1] == tokenizer.pad_idx)
            
            # Forward pass
            logits = model(traj_features, nearest_keys, targets, src_mask, tgt_mask)
            
            # Compute loss
            loss = criterion(logits.reshape(-1, logits.shape[-1]), targets[:, 1:].reshape(-1))
            
            # Backward pass
            optimizer.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            scheduler.step()
            
            # Track metrics
            train_loss += loss.item()
            predictions = logits.argmax(dim=-1)
            mask = (targets[:, 1:] != tokenizer.pad_idx)
            train_correct += ((predictions == targets[:, 1:]) & mask).sum().item()
            train_total += mask.sum().item()
            
            # Update progress
            if batch_idx % 10 == 0:
                acc = train_correct / max(train_total, 1)
                pbar.set_postfix({
                    'loss': f'{loss.item():.4f}', 
                    'acc': f'{acc:.2%}',
                    'lr': f'{scheduler.get_last_lr()[0]:.2e}'
                })
        
        train_acc = train_correct / train_total
        avg_train_loss = train_loss / len(train_loader)
        
        # Validation phase
        model.eval()
        val_correct_words = 0
        val_total_words = 0
        val_top5_correct = 0
        
        with torch.no_grad():
            pbar = tqdm(val_loader, desc=f'Epoch {epoch+1}/{num_epochs} [Val]')
            for batch in pbar:
                traj_features = batch['traj_features'].to(device)
                nearest_keys = batch['nearest_keys'].to(device)
                words = batch['word']
                
                # Create masks
                seq_lens = batch['seq_len']
                src_mask = torch.zeros(traj_features.shape[0], traj_features.shape[1], dtype=torch.bool, device=device)
                for i, seq_len in enumerate(seq_lens):
                    src_mask[i, seq_len:] = True
                
                # Generate with beam search
                generated_words = model.generate_beam(
                    traj_features, nearest_keys, tokenizer, src_mask, beam_size=5
                )
                
                # Compute accuracy
                for gen_word, true_word in zip(generated_words, words):
                    val_total_words += 1
                    if gen_word == true_word:
                        val_correct_words += 1
                
                # Update progress
                word_acc = val_correct_words / max(val_total_words, 1)
                pbar.set_postfix({'word_acc': f'{word_acc:.2%}'})
        
        val_word_acc = val_correct_words / val_total_words
        
        # Print epoch summary
        print(f"\nEpoch {epoch+1}/{num_epochs}")
        print(f"  Train - Loss: {avg_train_loss:.4f}, Char Acc: {train_acc:.2%}")
        print(f"  Val   - Word Acc: {val_word_acc:.2%}")
        
        # Save checkpoint if improved
        if val_word_acc > best_val_acc:
            best_val_acc = val_word_acc
            patience_counter = 0
            
            checkpoint_path = checkpoint_dir / f'full-model-{epoch+1:02d}-{val_word_acc:.3f}.ckpt'
            torch.save({
                'epoch': epoch,
                'model_state_dict': model.state_dict(),
                'optimizer_state_dict': optimizer.state_dict(),
                'scheduler_state_dict': scheduler.state_dict(),
                'val_word_acc': val_word_acc,
                'train_acc': train_acc,
            }, checkpoint_path)
            print(f"  ✓ New best model saved: {checkpoint_path}")
            
            # Check if target reached
            if val_word_acc >= 0.99:
                print("\n" + "="*60)
                print(f"🎉 TARGET ACHIEVED! {val_word_acc:.1%} word accuracy!")
                print("Successfully matched original model performance!")
                print("="*60)
                
                # Run test set evaluation
                print("\nEvaluating on test set...")
                test_correct = 0
                test_total = 0
                
                with torch.no_grad():
                    for batch in tqdm(test_loader, desc="Test"):
                        traj_features = batch['traj_features'].to(device)
                        nearest_keys = batch['nearest_keys'].to(device)
                        words = batch['word']
                        
                        seq_lens = batch['seq_len']
                        src_mask = torch.zeros(traj_features.shape[0], traj_features.shape[1], dtype=torch.bool, device=device)
                        for i, seq_len in enumerate(seq_lens):
                            src_mask[i, seq_len:] = True
                        
                        generated_words = model.generate_beam(
                            traj_features, nearest_keys, tokenizer, src_mask, beam_size=5
                        )
                        
                        for gen_word, true_word in zip(generated_words, words):
                            test_total += 1
                            if gen_word == true_word:
                                test_correct += 1
                
                test_acc = test_correct / test_total
                print(f"Test Set Accuracy: {test_acc:.2%}")
                break
        else:
            patience_counter += 1
            if patience_counter >= patience:
                print(f"\nEarly stopping - no improvement for {patience} epochs")
                print(f"Best validation accuracy: {best_val_acc:.2%}")
                break
        
        print("-"*60)
    
    if best_val_acc < 0.70:
        print(f"\nTraining complete. Best validation accuracy: {best_val_acc:.2%}")
        print("Consider:")
        print("- Training for more epochs")
        print("- Adjusting hyperparameters")
        print("- Using data augmentation")


if __name__ == "__main__":
    train_full_model()